package org.ethereum.sync;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.config.CommonConfig;
import org.ethereum.config.SystemProperties;
import org.ethereum.core.*;
import org.ethereum.crypto.HashUtil;
import org.ethereum.datasource.DbSource;
import org.ethereum.datasource.inmem.HashMapDB;
import org.ethereum.db.DbFlushManager;
import org.ethereum.db.IndexedBlockStore;
import org.ethereum.listener.CompositeEthereumListener;
import org.ethereum.listener.EthereumListener;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.net.client.Capability;
import org.ethereum.net.eth.handler.Eth63;
import org.ethereum.net.message.ReasonCode;
import org.ethereum.net.rlpx.discover.NodeHandler;
import org.ethereum.net.server.Channel;
import org.ethereum.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

import static org.ethereum.util.CompactEncoder.hasTerminator;

/**
 * Created by Anton Nashatyrev on 24.10.2016.
 */
@Component
public class FastSyncManager {
    private final static Logger logger = LoggerFactory.getLogger("sync");

    private final static long REQUEST_TIMEOUT = 5 * 1000;
    private final static int REQUEST_MAX_NODES = 384;
    private final static int NODE_QUEUE_BEST_SIZE = 100_000;
    private final static int MIN_PEERS_FOR_PIVOT_SELECTION = 5;
    private final static int FORCE_SYNC_TIMEOUT = 60 * 1000;
    private final static int PIVOT_DISTANCE_FROM_HEAD = 1024;

    private static final Capability ETH63_CAPABILITY = new Capability(Capability.ETH, (byte) 63);

    @Autowired
    private SystemProperties config;

    @Autowired
    private SyncPool pool;

    @Autowired
    private BlockchainImpl blockchain;

    @Autowired
    private IndexedBlockStore blockStore;

    @Autowired
    private SyncManager syncManager;

    @Autowired
    @Qualifier("stateDS")
    DbSource<byte[]> stateDS = new HashMapDB<>();

    @Autowired
    private Repository repository;

    @Autowired
    DbFlushManager dbFlushManager;

    @Autowired
    FastSyncDownloader downloader;

    @Autowired
    CompositeEthereumListener listener;

    @Autowired
    ApplicationContext applicationContext;

    int nodesInserted = 0;
    int lastNodeCommit = 0;
    private ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();

    private BlockingQueue<TrieNodeRequest> dbWriteQueue = new LinkedBlockingQueue<>();

    void init() {
        new Thread("FastSyncDBWriter") {
            @Override
            public void run() {
                try {
                    while (true) {
                        TrieNodeRequest request = dbWriteQueue.take();
                        nodesInserted++;
                        repository.addRawNode(request.nodeHash, request.response);
                        if (nodesInserted - lastNodeCommit >= 100) {
                            commitNodes();
                        }
                    }
                } catch (Exception e) {
                    logger.error("Fatal FastSync error while writing data", e);
                }
            }
        }.start();

        new Thread("FastSyncLoop") {
            @Override
            public void run() {
                try {
                    main();
                } catch (Exception e) {
                    logger.error("Fatal FastSync loop error", e);
                }
            }
        }.start();
    }

    private synchronized void commitNodes() {
        repository.commit();
        dbFlushManager.commit();
        lastNodeCommit = nodesInserted;
    }

    enum TrieNodeType {
        STATE,
        STORAGE,
        CODE
    }

    private class TrieNodeRequest {
        TrieNodeType type;
        byte[] nodeHash;
        byte[] response;
        final Map<Long, Long> requestSent = new HashMap<>();

        TrieNodeRequest(TrieNodeType type, byte[] nodeHash) {
            this.type = type;
            this.nodeHash = nodeHash;
        }

        List<TrieNodeRequest> createChildRequests() {
            if (type == TrieNodeType.CODE) {
                return Collections.emptyList();
            }

            List<Object> node = Value.fromRlpEncoded(response).asList();
            List<TrieNodeRequest> ret = new ArrayList<>();
            if (type == TrieNodeType.STATE) {
                if (node.size() == 2 && hasTerminator((byte[]) node.get(0))) {
                    byte[] nodeValue = (byte[]) node.get(1);
                    AccountState state = new AccountState(nodeValue);

                    if (!FastByteComparisons.equal(HashUtil.EMPTY_DATA_HASH, state.getCodeHash())) {
                        ret.add(new TrieNodeRequest(TrieNodeType.CODE, state.getCodeHash()));
                    }
                    if (!FastByteComparisons.equal(HashUtil.EMPTY_TRIE_HASH, state.getStateRoot())) {
                        ret.add(new TrieNodeRequest(TrieNodeType.STORAGE, state.getStateRoot()));
                    }
                    return ret;
                }
            }

            List<byte[]> childHashes = getChildHashes(node);
            for (byte[] childHash : childHashes) {
                ret.add(new TrieNodeRequest(type, childHash));
            }
            return ret;
        }

        public void reqSent(Long requestId) {
            synchronized (FastSyncManager.this) {
                Long timestamp = System.currentTimeMillis();
                requestSent.put(requestId, timestamp);
            }
        }

        public Set<Long> requestIdsSnapshot() {
            synchronized (FastSyncManager.this) {
                return new HashSet<Long>(requestSent.keySet());
            }
        }

        @Override
        public String toString() {
            return "TrieNodeRequest{" +
                    "type=" + type +
                    ", nodeHash=" + Hex.toHexString(nodeHash) +
                    '}';
        }
    }

    private static List<byte[]> getChildHashes(List<Object> siblings) {
        List<byte[]> ret = new ArrayList<>();
        if (siblings.size() == 2) {
            Value val = new Value(siblings.get(1));
            if (val.isHashCode() && !hasTerminator((byte[]) siblings.get(0)))
                ret.add(val.asBytes());
        } else {
            for (int j = 0; j < 16; ++j) {
                Value val = new Value(siblings.get(j));
                if (val.isHashCode())
                    ret.add(val.asBytes());
            }
        }
        return ret;
    }

    Deque<TrieNodeRequest> nodesQueue = new LinkedBlockingDeque<>();
    ByteArrayMap<TrieNodeRequest> pendingNodes = new ByteArrayMap<>();
    Long requestId = 0L;

    private synchronized void purgePending(byte[] hash) {
        TrieNodeRequest request = pendingNodes.get(hash);
        if (request.requestSent.isEmpty()) pendingNodes.remove(hash);
    }

    synchronized void processTimeouts() {
        long cur = System.currentTimeMillis();
        for (TrieNodeRequest request : new ArrayList<>(pendingNodes.values())) {
            Iterator<Map.Entry<Long, Long>> reqIterator = request.requestSent.entrySet().iterator();
            while (reqIterator.hasNext()) {
                Map.Entry<Long, Long> requestEntry = reqIterator.next();
                if (cur - requestEntry.getValue() > REQUEST_TIMEOUT) {
                    reqIterator.remove();
                    purgePending(request.nodeHash);
                    nodesQueue.addFirst(request);
                }
            }
        }
    }

    synchronized void processResponse(TrieNodeRequest req) {
        dbWriteQueue.add(req);
        for (TrieNodeRequest childRequest : req.createChildRequests()) {
            if (nodesQueue.size() > NODE_QUEUE_BEST_SIZE) {
                // reducing queue by traversing tree depth-first
                nodesQueue.addFirst(childRequest);
            } else {
                // enlarging queue by traversing tree breadth-first
                nodesQueue.add(childRequest);
            }
        }
    }

    boolean requestNextNodes(int cnt) {
        final Channel idle = pool.getAnyIdle();

        if (idle != null) {
            final List<byte[]> hashes = new ArrayList<>();
            final List<TrieNodeRequest> requestsSent = new ArrayList<>();
            final Set<Long> sentRequestIds = new HashSet<>();
            synchronized (this) {
                for (int i = 0; i < cnt && !nodesQueue.isEmpty(); i++) {
                    TrieNodeRequest req = nodesQueue.poll();
                    hashes.add(req.nodeHash);
                    TrieNodeRequest request = pendingNodes.get(req.nodeHash);
                    if (request == null) {
                        pendingNodes.put(req.nodeHash, req);
                        request = req;
                    }
                    sentRequestIds.add(requestId);
                    request.reqSent(requestId);
                    requestId++;
                    requestsSent.add(request);
                }
            }
            if (hashes.size() > 0) {
                logger.trace("Requesting " + hashes.size() + " nodes from peer: " + idle);
                ListenableFuture<List<Pair<byte[], byte[]>>> nodes = ((Eth63) idle.getEthHandler()).requestTrieNodes(hashes);
                final long reqTime = System.currentTimeMillis();
                Futures.addCallback(nodes, new FutureCallback<List<Pair<byte[], byte[]>>>() {
                    @Override
                    public void onSuccess(List<Pair<byte[], byte[]>> result) {
                        try {
                            synchronized (FastSyncManager.this) {
                                logger.trace("Received " + result.size() + " nodes (of " + hashes.size() + ") from peer: " + idle);
                                for (Pair<byte[], byte[]> pair : result) {
                                    TrieNodeRequest request = pendingNodes.get(pair.getKey());
                                    if (request == null) {
                                        long t = System.currentTimeMillis();
                                        logger.debug("Received node which was not requested: " + Hex.toHexString(pair.getKey()) + " from " + idle);
                                        return;
                                    }
                                    Set<Long> intersection = request.requestIdsSnapshot();
                                    intersection.retainAll(sentRequestIds);
                                    if (!intersection.isEmpty()) {
                                        Long inter = intersection.iterator().next();
                                        request.requestSent.remove(inter);
                                        purgePending(pair.getKey());
                                        request.response = pair.getValue();
                                        processResponse(request);
                                    }
                                }

                                FastSyncManager.this.notifyAll();

                                idle.getNodeStatistics().eth63NodesRequested.add(hashes.size());
                                idle.getNodeStatistics().eth63NodesReceived.add(result.size());
                                idle.getNodeStatistics().eth63NodesRetrieveTime.add(System.currentTimeMillis() - reqTime);
                            }
                        } catch (Exception e) {
                            logger.error("Unexpected error processing nodes", e);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        logger.warn("Error with Trie Node request: " + t);
                        synchronized (FastSyncManager.this) {
                            for (byte[] hash : hashes) {
                                final TrieNodeRequest request = pendingNodes.get(hash);
                                if (request == null) continue;
                                Set<Long> intersection = request.requestIdsSnapshot();
                                intersection.retainAll(sentRequestIds);
                                if (!intersection.isEmpty()) {
                                    Long inter = intersection.iterator().next();
                                    request.requestSent.remove(inter);
                                    nodesQueue.addFirst(request);
                                    purgePending(hash);
                                }
                            }
                            FastSyncManager.this.notifyAll();
                        }
                    }
                });
                return true;
            } else {
//                idle.getEthHandler().setStatus(SyncState.IDLE);
                return false;
            }
        } else {
            return false;
        }
    }

    void retrieveLoop() {
        try {
            while (!nodesQueue.isEmpty() || !pendingNodes.isEmpty()) {
                try {
                    processTimeouts();

                    while (requestNextNodes(REQUEST_MAX_NODES)) ;

                    synchronized (this) {
                        wait(10);
                    }
                    logStat();
                } catch (InterruptedException e) {
                    throw e;
                } catch (Throwable t) {
                    logger.error("Error", t);
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Main fast sync loop was interrupted", e);
        }
    }

    long last = 0;
    long lastNodeCount = 0;

    private void logStat() {
        long cur = System.currentTimeMillis();
        if (cur - last > 5000) {
            logger.info("FastSync: received: " + nodesInserted + ", known: " + nodesQueue.size() + ", pending: " + pendingNodes.size()
                    + String.format(", nodes/sec: %1$.2f", 1000d * (nodesInserted - lastNodeCount) / (cur - last)));
            last = cur;
            lastNodeCount = nodesInserted;
        }
    }

    public void main() {

        if (blockchain.getBestBlock().getNumber() == 0) {
            BlockHeader pivot = getPivotBlock();
            if (pivot.getNumber() > 0) {

                pool.setNodesSelector(new Functional.Predicate<NodeHandler>() {
                    @Override
                    public boolean test(NodeHandler handler) {
                        if (!handler.getNodeStatistics().capabilities.contains(ETH63_CAPABILITY))
                            return false;
                        return true;
                    }
                });

                byte[] pivotStateRoot = pivot.getStateRoot();
                TrieNodeRequest request = new TrieNodeRequest(TrieNodeType.STATE, pivotStateRoot);
                nodesQueue.add(request);
                logger.info("FastSync: downloading state trie at pivot block: " + pivot.getShortDescr());

                stateDS.put(CommonConfig.FASTSYNC_DB_KEY, new byte[]{1});

                retrieveLoop();

                logger.info("FastSync: state trie download complete!");
                last = 0;
                logStat();

                repository.commit();
                dbFlushManager.flush();

                logger.info("FastSync: downloading 256 blocks prior to pivot block (" + pivot.getShortDescr() + ")");
                downloader.startImporting(pivot.getHash(), 260);
                downloader.waitForStop();

                logger.info("FastSync: complete downloading 256 blocks prior to pivot block (" + pivot.getShortDescr() + ")");

                blockchain.setBestBlock(blockStore.getBlockByHash(pivot.getHash()));
                dbFlushManager.flush();

                logger.info("FastSync: proceeding to regular sync...");

                final CountDownLatch syncDoneLatch = new CountDownLatch(1);
                listener.addListener(new EthereumListenerAdapter() {
                    @Override
                    public void onSyncDone(SyncState state) {
                        syncDoneLatch.countDown();
                    }
                });
                syncManager.initRegularSync(EthereumListener.SyncState.UNSECURE);
                logger.info("FastSync: waiting for regular sync to reach the blockchain head...");

                try {
                    syncDoneLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                logger.info("FastSync: regular sync reached the blockchain head.");


                logger.info("FastSync: downloading headers from pivot down to genesis block for ensure pivot block is secure...");
                HeadersDownloader headersDownloader = applicationContext.getBean(HeadersDownloader.class);
                headersDownloader.init(pivot.getHash());
                headersDownloader.waitForStop();
                if (!FastByteComparisons.equal(headersDownloader.getGenesisHash(), config.getGenesis().getHash())) {
                    logger.error("FASTSYNC FATAL ERROR: after downloading header chain starting from the pivot block (" +
                            pivot.getShortDescr() + ") obtained genesis block doesn't match ours: " + Hex.toHexString(headersDownloader.getGenesisHash()));
                    logger.error("Can't recover and exiting now. You need to restart from scratch (all DBs will be reset)");
                    System.exit(-666);
                }
                listener.onSyncDone(EthereumListener.SyncState.SECURE);
                logger.info("FastSync: all headers downloaded. The state is SECURE now.");

                logger.info("FastSync: Downloading Block bodies...");

                BlockBodiesDownloader blockBodiesDownloader = applicationContext.getBean(BlockBodiesDownloader.class);
                blockBodiesDownloader.startImporting();
                blockBodiesDownloader.waitForStop();

                logger.info("FastSync: Block bodies downloaded");

                logger.info("FastSync: Downloading receipts...");

                ReceiptsDownloader receiptsDownloader = applicationContext.getBean
                        (ReceiptsDownloader.class, 1, pivot.getNumber() + 1);
                receiptsDownloader.startImporting();
                receiptsDownloader.waitForStop();

                logger.info("FastSync: receipts downloaded");

                logger.info("FastSync: updating totDifficulties starting from the pivot block...");
                blockchain.updateBlockTotDifficulties((int) pivot.getNumber());
                synchronized (blockchain) {
                    Block bestBlock = blockchain.getBestBlock();
                    BigInteger totalDifficulty = blockchain.getTotalDifficulty();
                    logger.info("FastSync: totDifficulties updated: bestBlock: " + bestBlock.getShortDescr() + ", totDiff: " + totalDifficulty);
                }

                stateDS.delete(CommonConfig.FASTSYNC_DB_KEY);
                repository.commit();
                dbFlushManager.flush();

                pool.setNodesSelector(null);
                listener.onSyncDone(EthereumListener.SyncState.COMPLETE);
                logger.info("FastSync: Full sync done.");
            } else {
                logger.info("FastSync: too short blockchain, proceeding with regular sync...");
                syncManager.initRegularSync(EthereumListener.SyncState.COMPLETE);
            }
        } else {
            logger.info("FastSync: fast sync was completed, best block: (" + blockchain.getBestBlock().getShortDescr() + "). " +
                    "Continue with regular sync...");
            syncManager.initRegularSync(EthereumListener.SyncState.COMPLETE);
        }

        // set indicator that we can send [STATUS] with the latest block
        // before we should report best block #0
    }

    private BlockHeader getPivotBlock() {
        byte[] pivotBlockHash = config.getFastSyncPivotBlockHash();
        long pivotBlockNumber = 0;

        long start = System.currentTimeMillis();
        long s = start;

        if (pivotBlockHash != null) {
            logger.info("FastSync: fetching trusted pivot block with hash " + Hex.toHexString(pivotBlockHash));
        } else {
            logger.info("FastSync: looking for best block number...");
            BlockIdentifier bestKnownBlock;

            while (true) {
                List<Channel> allIdle = pool.getAllIdle();

                long forceSyncRemains = FORCE_SYNC_TIMEOUT - (System.currentTimeMillis() - start);

                if (allIdle.size() >= MIN_PEERS_FOR_PIVOT_SELECTION || forceSyncRemains < 0 && !allIdle.isEmpty()) {
                    Channel bestPeer = allIdle.get(0);
                    for (Channel channel : allIdle) {
                        if (bestPeer.getEthHandler().getBestKnownBlock().getNumber() < channel.getEthHandler().getBestKnownBlock().getNumber()) {
                            bestPeer = channel;
                        }
                    }
                    bestKnownBlock = bestPeer.getEthHandler().getBestKnownBlock();
                    if (bestKnownBlock.getNumber() > 1000) {
                        logger.info("FastSync: best block " + bestKnownBlock + " found with peer " + bestPeer);
                        break;
                    }
                }

                long t = System.currentTimeMillis();
                if (t - s > 5000) {
                    logger.info("FastSync: waiting for at least " + MIN_PEERS_FOR_PIVOT_SELECTION + " peers or " + forceSyncRemains / 1000 + " sec to select pivot block... ("
                            + allIdle.size() + " peers so far)");
                    s = t;
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            pivotBlockNumber = Math.max(bestKnownBlock.getNumber() - PIVOT_DISTANCE_FROM_HEAD, 0);
            logger.info("FastSync: fetching pivot block #" + pivotBlockNumber);
        }

        try {
            while (true) {
                Channel bestIdle = pool.getAnyIdle();
                if (bestIdle != null) {
                    try {
                        ListenableFuture<List<BlockHeader>> future = pivotBlockHash == null ?
                                bestIdle.getEthHandler().sendGetBlockHeaders(pivotBlockNumber, 1, false) :
                                bestIdle.getEthHandler().sendGetBlockHeaders(pivotBlockHash, 1, 0, false);
                        List<BlockHeader> blockHeaders = future.get(3, TimeUnit.SECONDS);
                        if (!blockHeaders.isEmpty()) {
                            BlockHeader ret = blockHeaders.get(0);

                            if (pivotBlockHash != null && !FastByteComparisons.equal(pivotBlockHash, ret.getHash())) {
                                logger.warn("Peer " + bestIdle + " returned pivot block with another hash: " +
                                        Hex.toHexString(ret.getHash()) + " Dropping the peer.");
                                bestIdle.disconnect(ReasonCode.USELESS_PEER);
                                continue;
                            }

                            logger.info("Pivot header fetched: " + ret.getShortDescr());
                            return ret;
                        } else {
                            logger.warn("Peer " + bestIdle + " doesn't returned correct pivot block. Dropping the peer.");
                            bestIdle.getNodeStatistics().wrongFork = true;
                            bestIdle.disconnect(ReasonCode.USELESS_PEER);
                            continue;
                        }
                    } catch (TimeoutException e) {
                        logger.debug("Timeout waiting for answer", e);
                    }
                }

                long t = System.currentTimeMillis();
                if (t - s > 5000) {
                    logger.info("FastSync: waiting for a peer to fetch pivot block...");
                    s = t;
                }

                Thread.sleep(500);
            }
        } catch (Exception e) {
            logger.error("Unexpected", e);
            throw new RuntimeException(e);
        }
    }
}
