/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.consensus;

import static io.xdag.utils.BytesUtils.compareTo;
import static io.xdag.utils.BytesUtils.equalBytes;

import io.xdag.Kernel;
import io.xdag.config.Constants.MessageType;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagBlock;
import io.xdag.core.XdagField;
import io.xdag.core.XdagState;
import io.xdag.crypto.Hash;
import io.xdag.listener.Listener;
import io.xdag.mine.MinerChannel;
import io.xdag.mine.manager.AwardManager;
import io.xdag.mine.manager.MinerManager;
import io.xdag.mine.miner.MinerCalculate;
import io.xdag.net.manager.XdagChannelManager;
import io.xdag.net.message.Message;
import io.xdag.randomx.RandomX;
import io.xdag.randomx.RandomXMemory;
import io.xdag.utils.XdagSha256Digest;
import io.xdag.utils.XdagTime;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

@Slf4j
public class XdagPow implements PoW, Listener, Runnable {

    private final Kernel kernel;
    protected BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    protected Timer timer;
    protected Broadcaster broadcaster;
    // 当前区块
    protected Block generateBlock;
    protected Bytes32 minShare;
    protected Bytes32 minHash;
    protected XdagChannelManager channelMgr;
    protected Blockchain blockchain;
    protected Bytes32 globalPretop;
    protected Task currentTask;
    protected long taskIndex = 0;
    /**
     * 存放的是过去十六个区块的hash
     */
    protected List<Bytes32> blockHashs = new CopyOnWriteArrayList<>();
    /**
     * 存放的是最小的hash
     */
    protected List<Bytes32> minShares = new CopyOnWriteArrayList<>(new ArrayList<>(16));
    /**
     * 引入矿工与奖励
     */
    protected AwardManager awardManager;
    protected MinerManager minerManager;
    protected RandomX randomXUtils;
    private boolean isRunning = false;

    public XdagPow(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.channelMgr = kernel.getChannelMgr();
        this.timer = new Timer();
        this.broadcaster = new Broadcaster();
        this.minerManager = kernel.getMinerManager();
        this.awardManager = kernel.getAwardManager();

        this.randomXUtils = kernel.getRandomXUtils();
    }

    @Override
    public void start() {
        if (!this.isRunning) {
            this.isRunning = true;

            this.minerManager = kernel.getMinerManager();

            // 容器的初始化
            for (int i = 0; i < 16; i++) {
                this.blockHashs.add(null);
                this.minShares.add(null);
            }

            new Thread(this.timer, "xdag-pow-timer").start();
            new Thread(this, "xdag-pow-main").start();
            new Thread(this.broadcaster, "xdag-pow-broadcaster").start();
        }
    }

    @Override
    public void stop() {
        if (this.isRunning) {
            this.isRunning = false;
            this.timer.isRunning = false;
            this.broadcaster.isRunning = false;
        }
    }

    public void newBlock() {
        log.debug("Start new block generate....");
        long sendTime = XdagTime.getMainTime();
        resetTimeout(sendTime);

        if (randomXUtils != null && randomXUtils.isRandomxFork(XdagTime.getEpoch(sendTime))) {
            if (randomXUtils.getRandomXPoolMemIndex() == 0) {
                randomXUtils.setRandomXPoolMemIndex((randomXUtils.getRandomXHashEpochIndex() - 1) & 1);
            }

            if (randomXUtils.getRandomXPoolMemIndex() == -1) {

                long switchTime0 = randomXUtils.getGlobalMemory()[0] == null ? 0
                        : randomXUtils.getGlobalMemory()[0].getSwitchTime();
                long switchTime1 = randomXUtils.getGlobalMemory()[1] == null ? 0
                        : randomXUtils.getGlobalMemory()[1].getSwitchTime();

                if (switchTime0 > switchTime1) {
                    if (XdagTime.getEpoch(sendTime) > switchTime0) {
                        randomXUtils.setRandomXPoolMemIndex(2);
                    } else {
                        randomXUtils.setRandomXPoolMemIndex(1);
                    }
                } else {
                    if (XdagTime.getEpoch(sendTime) > switchTime1) {
                        randomXUtils.setRandomXPoolMemIndex(1);
                    } else {
                        randomXUtils.setRandomXPoolMemIndex(2);
                    }
                }
            }

            long randomXMemIndex = randomXUtils.getRandomXPoolMemIndex() + 1;
            RandomXMemory memory = randomXUtils.getGlobalMemory()[(int) (randomXMemIndex) & 1];

            if ((XdagTime.getEpoch(XdagTime.getMainTime()) >= memory.getSwitchTime()) && (memory.getIsSwitched()
                    == 0)) {
                randomXUtils.setRandomXPoolMemIndex(randomXUtils.getRandomXPoolMemIndex() + 1);
                memory.setIsSwitched(1);
            }

            generateBlock = generateRandomXBlock(sendTime);
        } else {
            generateBlock = generateBlock(sendTime);
        }
    }


    public Block generateRandomXBlock(long sendTime) {
        // 新增任务
        log.debug("Generate RandomX block...");
        taskIndex++;

        Block block = blockchain.createNewBlock(null, null, true, null);
        block.signOut(kernel.getWallet().getDefKey());

        minShare = Bytes32.wrap(RandomUtils.nextBytes(32));
        block.setNonce(minShare);

//        minHash = Hex.decode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        minHash = Bytes32.fromHexString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

        currentTask = createTaskByRandomXBlock(block, sendTime);

        // 更新poolminer的贡献
        log.debug("Send randomx task to Miners");
        minerManager.updateTask(currentTask);
        awardManager.onNewTask(currentTask);

        return block;
    }

    public Block generateBlock(long sendTime) {
        // 新增任务
        taskIndex++;

        Block block = blockchain.createNewBlock(null, null, true, null);
        block.signOut(kernel.getWallet().getDefKey());

        minShare = Bytes32.wrap(RandomUtils.nextBytes(32));
        block.setNonce(minShare);
        // 初始nonce, 计算minhash但不作为最终hash
        minHash = block.recalcHash();

        currentTask = createTaskByNewBlock(block, sendTime);
        // 发送给矿工
        log.debug("Send origin task to Miners");
        // 更新poolminer的贡献
        minerManager.updateTask(currentTask);
        awardManager.onNewTask(currentTask);

        return block;
    }

    protected void resetTimeout(long timeout) {
        timer.timeout(timeout);
        events.removeIf(e -> e.type == Event.Type.TIMEOUT);
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    /**
     * 每收到一个miner的信息 之后都会在这里进行一次计算
     */
    @Override
    public void receiveNewShare(MinerChannel channel, Message msg) {
        if (!this.isRunning) {
            return;
        }

        XdagField shareInfo = new XdagField(msg.getEncoded().mutableCopy());
        log.debug("Receive share From PoolChannel, Shareinfo:{}", shareInfo.getData().toHexString());
        events.add(new Event(Event.Type.NEW_SHARE, shareInfo, channel));
    }

    public void receiveNewPretop(Bytes pretop) {
        if (!this.isRunning) {
            return;
        }
        if (!equalBytes(pretop.toArray(), globalPretop.toArray())) {
            globalPretop = Bytes32.wrap(blockchain.getXdagTopStatus().getPreTop());
            events.add(new Event(Event.Type.NEW_PRETOP, pretop));
        }
    }

    protected void onNewShare(XdagField shareInfo, MinerChannel channel) {
        try {
            Bytes32 hash;
            // if randomx fork
            if (kernel.getRandomXUtils().isRandomxFork(currentTask.getTaskTime())) {
                MutableBytes taskData = MutableBytes.create(64);
//                currentTask.getTask()[0].getData().copyTo(taskData, 0);
                taskData.set(0, currentTask.getTask()[0].getData());
//                shareInfo.getData().reverse().copyTo(taskData, 32);
                taskData.set(32, shareInfo.getData().reverse());
                hash = Bytes32.wrap(kernel.getRandomXUtils()
                        .randomXPoolCalcHash(taskData, taskData.size(), currentTask.getTaskTime()).reverse());
            } else {
                XdagSha256Digest digest = new XdagSha256Digest(currentTask.getDigest());
//                hash = Bytes32.wrap(digest.sha256Final(Arrays.reverse(shareInfo.getData().toArray())));
                hash = Bytes32.wrap(digest.sha256Final(shareInfo.getData().reverse()));
            }

            if (compareTo(hash.toArray(), 0, 32, minHash.toArray(), 0, 32) < 0) {
                minHash = hash;
                minShare = Bytes32.wrap(shareInfo.getData().reverse());

                // put minshare into nonce
                generateBlock.setNonce(minShare);

                //myron
                int index = (int) ((currentTask.getTaskTime() >> 16) & kernel.getConfig().getPoolSpec()
                        .getAwardEpoch());
                // int index = (int) ((currentTask.getTaskTime() >> 16) & 7);
                minShares.set(index, minShare);
                blockHashs.set(index, generateBlock.recalcHash());

                log.debug("New MinHash :" + minHash.toHexString());
                log.debug("New MinShare :" + minShare.toHexString());

            }
            //update miner state
            MinerCalculate.updateMeanLogDiff(channel, currentTask, hash);
            MinerCalculate.calculateNopaidShares(kernel.getConfig(), channel, hash, currentTask.getTaskTime());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    protected void onTimeout() {
        if (generateBlock != null) {
            log.debug("Broadcast locally generated blockchain, waiting to be verified. block hash = [{}]",
                    generateBlock.getHash().toHexString());
            // 发送区块 如果有的话 然后开始生成新区块
            kernel.getBlockchain().tryToConnect(new Block(new XdagBlock(generateBlock.toBytes())));
            awardManager.addAwardBlock(minShare, generateBlock.getHash(), generateBlock.getTimestamp());
            BlockWrapper bw = new BlockWrapper(new Block(new XdagBlock(generateBlock.toBytes())),
                    kernel.getConfig().getNodeSpec().getTTL());

            broadcaster.broadcast(bw);
        }
        newBlock();
    }

    protected void onNewPreTop() {
        log.debug("Receive New PreTop");
        newBlock();
    }

    /**
     * 创建RandomX的任务
     */
    private Task createTaskByRandomXBlock(Block block, long sendTime) {
        Task newTask = new Task();
        XdagField[] task = new XdagField[2];

        RandomXMemory memory = randomXUtils.getGlobalMemory()[(int) randomXUtils.getRandomXPoolMemIndex() & 1];

//        Bytes32 rxHash = Hash.sha256(Bytes.wrap(BytesUtils.subArray(block.getXdagBlock().getData(),0,480)));
        Bytes32 rxHash = Hash.sha256(block.getXdagBlock().getData().slice(0, 480));

        // todo
        task[0] = new XdagField(rxHash.mutableCopy());
        task[1] = new XdagField(MutableBytes.wrap(memory.getSeed()));

        newTask.setTask(task);
        newTask.setTaskTime(XdagTime.getEpoch(sendTime));
        newTask.setTaskIndex(taskIndex);

        return newTask;
    }

    /**
     * 创建原始任务
     */
    private Task createTaskByNewBlock(Block block, long sendTime) {
        Task newTask = new Task();

        XdagField[] task = new XdagField[2];
        task[1] = block.getXdagBlock().getField(14);
//        byte[] data = new byte[448];
        MutableBytes data = MutableBytes.create(448);

//        System.arraycopy(block.getXdagBlock().getData(), 0, data, 0, 448);
        data.set(0, block.getXdagBlock().getData().slice(0, 448));

        XdagSha256Digest currentTaskDigest = new XdagSha256Digest();
        try {
            currentTaskDigest.sha256Update(data);
            byte[] state = currentTaskDigest.getState();
            task[0] = new XdagField(MutableBytes.wrap(state));
            currentTaskDigest.sha256Update(block.getXdagBlock().getField(14).getData());
        } catch (IOException e) {
            e.printStackTrace();
        }
        newTask.setTask(task);
        newTask.setTaskTime(XdagTime.getEpoch(sendTime));
        newTask.setTaskIndex(taskIndex);
        newTask.setDigest(currentTaskDigest);
        return newTask;
    }

    @Override
    public void run() {
        log.info("Main PoW start ....");
        resetTimeout(XdagTime.getEndOfEpoch(XdagTime.getCurrentTimestamp() + 64));
        // init pretop
        globalPretop = Bytes32.wrap(blockchain.getXdagTopStatus().getPreTop());
        while (this.isRunning) {
            try {
                Event ev = events.poll(10, TimeUnit.MILLISECONDS);
                if (ev == null) {
                    continue;
                }
                switch (ev.getType()) {
                    case NEW_DIFF:
                        break;
                    case NEW_SHARE:
                        onNewShare(ev.getData(), ev.getChannel());
                        break;
                    case TIMEOUT:
                        // TODO : 判断当前是否可以进行产块
                        if (kernel.getXdagState() == XdagState.STST || kernel.getXdagState() == XdagState.SYNC) {
                            onTimeout();
                        }
                        break;
                    case NEW_PRETOP:
                        if (kernel.getXdagState() == XdagState.STST || kernel.getXdagState() == XdagState.SYNC) {
                            onNewPreTop();
                        }
                        break;
                    default:
                        break;
                }
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void onMessage(io.xdag.listener.Message message, MessageType type) {
        if (type == MessageType.PRE_TOP) {
            receiveNewPretop(message.getData());
        } else if (type == MessageType.NEW_LINK) {
            BlockWrapper bw = new BlockWrapper(new Block(new XdagBlock(message.getData().toArray())),
                    kernel.getConfig().getNodeSpec().getTTL());
            broadcaster.broadcast(bw);
        }
    }

    public static class Event {

        private final Type type;
        private final Object data;
        private Object channel;

        public Event(Type type) {
            this(type, null);
        }

        public Event(Type type, Object data) {
            this.type = type;
            this.data = data;
        }

        public Event(Type type, Object data, Object channel) {
            this.type = type;
            this.data = data;
            this.channel = channel;
        }

        public Type getType() {
            return type;
        }

        @SuppressWarnings("unchecked")
        public <T> T getData() {
            return (T) data;
        }

        @SuppressWarnings("unchecked")
        public <T> T getChannel() {
            return (T) channel;
        }

        @Override
        public String toString() {
            return "Event [type=" + type + ", data=" + data + "]";
        }

        public enum Type {
            /**
             * Received a timeout signal.
             */
            TIMEOUT,
            /**
             * Received a new share message.
             */
            NEW_SHARE,
            /**
             * Received a new pretop message.
             */
            NEW_PRETOP,
            /**
             * Received a new largest diff message.
             */
            NEW_DIFF,
        }
    }

    // TODO: change to scheduleAtFixRate
    public class Timer implements Runnable {

        private long timeout;
        private boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            while (this.isRunning) {
                if (timeout != -1 && XdagTime.getCurrentTimestamp() > timeout) {
                    events.add(new Event(Event.Type.TIMEOUT));
                    timeout = -1;
                    continue;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        public void timeout(long sendtime) {
            if (sendtime < 0) {
                throw new IllegalArgumentException("Timeout can not be negative");
            }
            this.timeout = sendtime;
        }
    }

    public class Broadcaster implements Runnable {

        private final LinkedBlockingQueue<BlockWrapper> queue = new LinkedBlockingQueue<>();
        private boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            while (this.isRunning) {
                BlockWrapper bw = null;
                try {
                    bw = queue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
                if (bw != null) {
                    channelMgr.sendNewBlock(bw);

                }
            }
        }

        public void broadcast(BlockWrapper bw) {
            if (!queue.offer(bw)) {
                log.error("Failed to add a message to the broadcast queue: block = {}", bw.getBlock()
                        .getHash().toHexString());
            }
        }
    }
}
