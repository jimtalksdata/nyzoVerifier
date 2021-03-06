package co.nyzo.verifier;

import co.nyzo.verifier.messages.BlockRequest;
import co.nyzo.verifier.messages.BlockVote;
import co.nyzo.verifier.messages.MissingBlockRequest;
import co.nyzo.verifier.messages.MissingBlockResponse;
import co.nyzo.verifier.util.NotificationUtil;
import co.nyzo.verifier.util.PrintUtil;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class UnfrozenBlockManager {

    private static Map<Long, Map<ByteBuffer, Block>> unfrozenBlocks = new HashMap<>();
    private static Map<Long, Integer> thresholdOverrides = new HashMap<>();
    private static Map<Long, byte[]> hashOverrides = new HashMap<>();

    public static synchronized boolean registerBlock(Block block) {

        boolean registeredBlock = false;

        // Reject all blocks with invalid signatures. We should only be working one past the frozen edge, but we will
        // accept to the open edge in case we have gotten behind.
        long frozenEdgeHeight = BlockManager.getFrozenEdgeHeight();
        if (block != null && block.getBlockHeight() > frozenEdgeHeight && block.signatureIsValid() &&
                block.getBlockHeight() <= BlockManager.openEdgeHeight(true)) {

            // Get the map of blocks at this height.
            Map<ByteBuffer, Block> blocksAtHeight = unfrozenBlocks.get(block.getBlockHeight());
            if (blocksAtHeight == null) {
                blocksAtHeight = new HashMap<>();
                unfrozenBlocks.put(block.getBlockHeight(), blocksAtHeight);
            }

            // Check if the block is a simple duplicate (same hash).
            boolean alreadyContainsBlock = blocksAtHeight.containsKey(ByteBuffer.wrap(block.getHash()));

            // Check if the block has a valid verification timestamp. We cannot be sure of this, but we can filter out
            // some invalid blocks at this point.
            boolean verificationTimestampIntervalValid = true;
            if (!alreadyContainsBlock) {
                Block previousBlock = block.getPreviousBlock();
                if (previousBlock != null && previousBlock.getVerificationTimestamp() >
                        block.getVerificationTimestamp() - Block.minimumVerificationInterval) {
                    verificationTimestampIntervalValid = false;
                }
            }

            if (!alreadyContainsBlock && verificationTimestampIntervalValid) {

                // At this point, it is prudent to independently calculate the balance list. We only register the block
                // if we can calculate the balance list and if the has matches what we expect. This will ensure that no
                // blocks with invalid transactions are registered (they will be removed in the balance-list
                // calculation, and the hash will not match).
                BalanceList balanceList = BalanceListManager.balanceListForBlock(block, new StringBuilder());
                if (balanceList != null && ByteUtil.arraysAreEqual(balanceList.getHash(), block.getBalanceListHash())) {

                    blocksAtHeight.put(ByteBuffer.wrap(block.getHash()), block);
                    registeredBlock = true;

                    // Only keep the best 500 blocks at any level. For stability in the list, consider the just-added
                    // block to be the highest-scored, and only remove another block if it has a higher score than the
                    // new block.
                    if (blocksAtHeight.size() > 500 && !BlockManager.inGenesisCycle()) {
                        Block highestScoredBlock = block;
                        long highestScore = highestScoredBlock.chainScore(frozenEdgeHeight);
                        for (Block blockAtHeight : blocksAtHeight.values()) {
                            long score = blockAtHeight.chainScore(frozenEdgeHeight);
                            if (score > highestScore) {
                                highestScore = score;
                                highestScoredBlock = blockAtHeight;
                            }
                        }

                        blocksAtHeight.remove(ByteBuffer.wrap(highestScoredBlock.getHash()));
                    }
                }
            }
        }

        return registeredBlock;
    }

    public static synchronized void updateVote() {

        // Only vote for the first height past the frozen edge, and only continue if we have blocks.
        long frozenEdgeHeight = BlockManager.getFrozenEdgeHeight();
        long height = frozenEdgeHeight + 1;
        Map<ByteBuffer, Block> blocksForHeight = unfrozenBlocks.get(height);
        if (blocksForHeight != null && !blocksForHeight.isEmpty()) {

            // This will be the vote that we determine based on the current state. If this is different than our
            // current vote, we will broadcast it to the mesh.
            byte[] newVoteHash = null;

            if (hashOverrides.containsKey(height)) {

                // We always use an override if one is available.
                newVoteHash = hashOverrides.get(height);
            } else {
                Block newVoteBlock = null;

                // Get the current votes for this height. If a block has greater than 50% of the vote, vote for it
                // if its score allows voting yet. Otherwise, if the leading hash has a score that allowed voting more
                // than 10 seconds ago, vote for it even if it does not exceed 50%. This allows us to reach consensus
                // even if no hash exceeds 50%.
                AtomicInteger voteCountWrapper = new AtomicInteger(0);
                byte[] leadingHash = BlockVoteManager.leadingHashForHeight(height, voteCountWrapper);
                Block leadingHashBlock = unfrozenBlockAtHeight(height, leadingHash);
                if (leadingHashBlock != null) {
                    int voteCount = voteCountWrapper.get();
                    int votingPoolSize = BlockManager.inGenesisCycle() ? NodeManager.getMeshSize() :
                            BlockManager.currentCycleLength();
                    if ((voteCount > votingPoolSize / 2 && leadingHashBlock.getMinimumVoteTimestamp() <=
                            System.currentTimeMillis()) ||
                            leadingHashBlock.getMinimumVoteTimestamp() < System.currentTimeMillis() - 10000L) {
                        newVoteBlock = leadingHashBlock;
                    }
                }

                // If we did not determine a vote to agree with the rest of the mesh, then we independently choose the
                // block that we think is best.
                if (newVoteBlock == null) {

                    // Find the block with the lowest score at this height.
                    Block lowestScoredBlock = null;
                    long lowestChainScore = Long.MAX_VALUE;
                    for (Block block : blocksForHeight.values()) {
                        long blockChainScore = block.chainScore(frozenEdgeHeight);
                        if (lowestScoredBlock == null || blockChainScore < lowestChainScore) {
                            lowestChainScore = blockChainScore;
                            lowestScoredBlock = block;
                        }
                    }

                    if (lowestScoredBlock != null &&
                            lowestScoredBlock.getMinimumVoteTimestamp() <= System.currentTimeMillis()) {

                        newVoteBlock = lowestScoredBlock;
                    }
                }

                if (newVoteBlock != null) {
                    newVoteHash = newVoteBlock.getHash();
                }
            }

            // If we determined a vote and it is different than the previous vote, broadcast it to the mesh.
            if (newVoteHash != null &&
                    !ByteUtil.arraysAreEqual(newVoteHash, BlockVoteManager.getLocalVoteForHeight(height))) {

                castVote(height, newVoteHash);
            }
        }
    }

    private static synchronized void castVote(long height, byte[] hash) {

        // Register the vote locally and send it to the network.
        BlockVote vote = new BlockVote(height, hash, System.currentTimeMillis());
        BlockVoteManager.registerVote(Verifier.getIdentifier(), vote);
        Message message = new Message(MessageType.BlockVote19, vote);
        Message.broadcast(message);
    }

    public static synchronized void attemptToFreezeBlock() {

        long frozenEdgeHeight = BlockManager.getFrozenEdgeHeight();
        long heightToFreeze = frozenEdgeHeight + 1;

        // Get the vote tally for the height we are trying to freeze.
        AtomicInteger voteCountWrapper = new AtomicInteger(0);
        byte[] leadingHash = BlockVoteManager.leadingHashForHeight(heightToFreeze, voteCountWrapper);
        int voteCount = voteCountWrapper.get();

        // If the vote count is greater than 75% of the voting pool, we may be able to freeze the block.
        int votingPoolSize = BlockManager.inGenesisCycle() ? NodeManager.getMeshSize() :
                BlockManager.currentCycleLength();
        int voteCountThreshold = thresholdOverrides.containsKey(heightToFreeze) ?
                votingPoolSize * thresholdOverrides.get(heightToFreeze) / 100 :
                votingPoolSize * 3 / 4;
        if (voteCount > voteCountThreshold) {

            // Sleep for 0.5 seconds. If the vote is still over 75% for the same block after this period,
            // freeze the block.
            try {
                Thread.sleep(500L);
            } catch (Exception ignored) { }

            AtomicInteger secondVoteCountWrapper = new AtomicInteger(0);
            byte[] secondLeadingHash = BlockVoteManager.leadingHashForHeight(heightToFreeze, secondVoteCountWrapper);
            int secondVoteCount = secondVoteCountWrapper.get();

            if (secondVoteCount > voteCountThreshold && ByteUtil.arraysAreEqual(leadingHash, secondLeadingHash)) {
                Block block = unfrozenBlockAtHeight(heightToFreeze, leadingHash);
                if (block != null) {
                    BlockManager.freezeBlock(block);
                }
            }
        }

        // Clean up maps if we froze a block.
        if (BlockManager.getFrozenEdgeHeight() > frozenEdgeHeight) {

            // Remove blocks at or below the new frozen edge.
            frozenEdgeHeight = BlockManager.getFrozenEdgeHeight();
            for (Long height : new HashSet<>(unfrozenBlocks.keySet())) {
                if (height <= frozenEdgeHeight) {
                    unfrozenBlocks.remove(height);
                }
            }

            // Remove threshold overrides at or below the new frozen edge.
            for (Long height : new HashSet<>(thresholdOverrides.keySet())) {
                if (height <= frozenEdgeHeight) {
                    thresholdOverrides.remove(height);
                }
            }

            // Remove hash overrides at or below the new frozen edge.
            for (Long height : new HashSet<>(hashOverrides.keySet())) {
                if (height <= frozenEdgeHeight) {
                    hashOverrides.remove(height);
                }
            }
        }
    }

    public static void fetchMissingBlock(long height, byte[] hash) {

        NotificationUtil.send("fetching block " + height + " (" + PrintUtil.compactPrintByteArray(hash) +
                ") from mesh on " + Verifier.getNickname());
        Message blockRequest = new Message(MessageType.MissingBlockRequest25,
                new MissingBlockRequest(height, hash));
        Message.fetchFromRandomNode(blockRequest, new MessageCallback() {
            @Override
            public void responseReceived(Message message) {

                MissingBlockResponse response = (MissingBlockResponse) message.getContent();
                Block responseBlock = response.getBlock();
                if (responseBlock != null && ByteUtil.arraysAreEqual(responseBlock.getHash(), hash)) {
                    registerBlock(responseBlock);
                }
            }
        });
    }

    public static synchronized Set<Long> unfrozenBlockHeights() {

        return new HashSet<>(unfrozenBlocks.keySet());
    }

    public static int numberOfBlocksAtHeight(long height) {

        int number = 0;
        Map<ByteBuffer, Block> blocks = unfrozenBlocks.get(height);
        if (blocks != null) {
            number = blocks.size();
        }

        return number;
    }

    public static synchronized List<Block> allUnfrozenBlocks() {

        List<Block> allBlocks = new ArrayList<>();
        for (Map<ByteBuffer, Block> blocks : unfrozenBlocks.values()) {
            allBlocks.addAll(blocks.values());
        }

        return allBlocks;
    }

    public static synchronized List<Block> unfrozenBlocksAtHeight(long height) {

        return unfrozenBlocks.containsKey(height) ? new ArrayList<>(unfrozenBlocks.get(height).values()) :
                new ArrayList<>();
    }

    public static synchronized Block unfrozenBlockAtHeight(long height, byte[] hash) {

        Block block = null;
        if (hash != null) {
            Map<ByteBuffer, Block> blocksAtHeight = unfrozenBlocks.get(height);
            if (blocksAtHeight != null) {
                block = blocksAtHeight.get(ByteBuffer.wrap(hash));
            }
        }

        return block;
    }

    public static synchronized void purge() {

        unfrozenBlocks.clear();
    }

    public static synchronized void requestMissingBlocks() {

        long frozenEdgeHeight = BlockManager.getFrozenEdgeHeight();
        for (long height : BlockVoteManager.getHeights()) {
            if (height > frozenEdgeHeight) {
                for (ByteBuffer hash : BlockVoteManager.getHashesForHeight(height)) {
                    Block block = UnfrozenBlockManager.unfrozenBlockAtHeight(height, hash.array());
                    if (block == null) {
                        fetchMissingBlock(height, hash.array());
                    }
                }
            }
        }
    }

    public static synchronized void setThresholdOverride(long height, int threshold) {

        if (threshold == 0) {
            thresholdOverrides.remove(height);
        } else if (threshold < 100) {
            thresholdOverrides.put(height, threshold);
        }
    }

    public static synchronized void setHashOverride(long height, byte[] hash) {

        if (ByteUtil.isAllZeros(hash)) {
            hashOverrides.remove(height);
        } else {
            hashOverrides.put(height, hash);
        }
    }

    public static synchronized Map<Long, Integer> getThresholdOverrides() {

        return new HashMap<>(thresholdOverrides);
    }

    public static synchronized Map<Long, byte[]> getHashOverrides() {

        return new HashMap<>(hashOverrides);
    }
}
