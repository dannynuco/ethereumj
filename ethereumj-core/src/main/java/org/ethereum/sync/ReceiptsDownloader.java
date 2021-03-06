package org.ethereum.sync;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.ethereum.core.*;
import org.ethereum.db.DbFlushManager;
import org.ethereum.db.IndexedBlockStore;
import org.ethereum.db.TransactionStore;
import org.ethereum.net.eth.handler.Eth63;
import org.ethereum.net.server.Channel;
import org.ethereum.util.FastByteComparisons;
import org.ethereum.validator.BlockHeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Created by Anton Nashatyrev on 27.10.2016.
 */
@Component
@Scope("prototype")
public class ReceiptsDownloader {
    private final static Logger logger = LoggerFactory.getLogger("sync");

    @Autowired
    SyncPool syncPool;

    @Autowired
    IndexedBlockStore blockStore;

    @Autowired
    DbFlushManager dbFlushManager;

    @Autowired
    TransactionStore txStore;

    long fromBlock, toBlock;
    Set<Long> completedBlocks = new HashSet<>();

    long t;
    int cnt;

    Thread retrieveThread;
    private CountDownLatch stopLatch = new CountDownLatch(1);

    public ReceiptsDownloader(long fromBlock, long toBlock) {
        this.fromBlock = fromBlock;
        this.toBlock = toBlock;
    }

    public void startImporting() {
        retrieveThread = new Thread("FastsyncReceiptsFetchThread") {
            @Override
            public void run() {
                retrieveLoop();
            }
        };
        retrieveThread.start();
    }

    private List<List<Block>> getToDownload(int maxAskSize, int maxAsks) {
        List<Block> toDownload = getToDownload(maxAskSize * maxAsks);
        List<List<Block>> ret = new ArrayList<>();
        for (int i = 0; i < toDownload.size(); i += maxAskSize) {
            ret.add(toDownload.subList(i, Math.min(toDownload.size(), i + maxAskSize)));
        }
        return ret;
    }

    private synchronized List<Block> getToDownload(int maxSize) {
        List<Block> ret = new ArrayList<>();
        for (long i = fromBlock; i < toBlock && maxSize > 0; i++) {
            if (!completedBlocks.contains(i)) {
                ret.add(blockStore.getChainBlockByNumber(i));
                maxSize--;
            }
        }
        return ret;
    }

    private void processDownloaded(Block block, List<TransactionReceipt> receipts) {
        if (block.getNumber() >= fromBlock && validate(block, receipts)) {
            for (int i = 0; i < receipts.size(); i++) {
                TransactionReceipt receipt = receipts.get(i);
                TransactionInfo txInfo = new TransactionInfo(receipt, block.getHash(), (int) block.getNumber());
                txInfo.setTransaction(block.getTransactionsList().get(i));
                txStore.put(txInfo);
            }

            synchronized (this) {
                completedBlocks.add(block.getNumber());

                while (fromBlock < toBlock && completedBlocks.remove(fromBlock)) fromBlock++;

                if (fromBlock >= toBlock) finishDownload();

                cnt++;
                if (cnt % 1000 == 0) logger.info("FastSync: downloaded receipts for " + cnt + " blocks.");
            }
            dbFlushManager.commit();
        }
    }

    private boolean validate(Block block, List<TransactionReceipt> receipts) {
        byte[] receiptsRoot = BlockchainImpl.calcReceiptsTrie(receipts);
        return FastByteComparisons.equal(receiptsRoot, block.getReceiptsRoot());
    }

    private void retrieveLoop() {
        List<List<Block>> toDownload = Collections.emptyList();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (toDownload.isEmpty()) {
                    toDownload = getToDownload(100, 20);
                }

                Channel idle = syncPool.getAnyIdle();
                if (idle != null) {
                    final List<Block> list = toDownload.remove(0);
                    List<byte[]> req = new ArrayList<>();
                    for (Block header : list) {
                        req.add(header.getHash());
                    }
                    ListenableFuture<List<List<TransactionReceipt>>> future =
                            ((Eth63) idle.getEthHandler()).requestReceipts(req);
                    if (future != null) {
                        Futures.addCallback(future, new FutureCallback<List<List<TransactionReceipt>>>() {
                            @Override
                            public void onSuccess(List<List<TransactionReceipt>> result) {
                                for (int i = 0; i < result.size(); i++) {
                                    processDownloaded(list.get(i), result.get(i));
                                }
                            }
                            @Override
                            public void onFailure(Throwable t) {}
                        });
                    }
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (Exception e) {
                logger.warn("Unexpected during receipts downloading", e);
            }
        }
    }

    public void stop() {
        retrieveThread.interrupt();
        stopLatch.countDown();
    }

    public void waitForStop() {
        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected void finishDownload() {
        stop();
    }
}
