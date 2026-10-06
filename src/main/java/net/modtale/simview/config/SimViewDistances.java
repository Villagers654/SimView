package net.modtale.simview.config;

import com.hypixel.hytale.math.util.ChunkUtil;

public final class SimViewDistances {
  public static final int SECTION_SIZE_BLOCKS = ChunkUtil.SIZE;
  public static final int MAX_RADIUS_SECTIONS = 64;
  public static final int MAX_DISTANCE_BLOCKS = MAX_RADIUS_SECTIONS * SECTION_SIZE_BLOCKS;

  private SimViewDistances() {}

  public static int sectionsToBlocks(int sections) {
    return (int) Math.clamp((long) sections * SECTION_SIZE_BLOCKS, 0L, Integer.MAX_VALUE);
  }

  public static int blocksToSections(int blocks) {
    return Math.max(0, blocks) / SECTION_SIZE_BLOCKS;
  }

  public static int normalizeBlocks(int blocks) {
    return sectionsToBlocks(blocksToSections(Math.clamp(blocks, 0, MAX_DISTANCE_BLOCKS)));
  }

  public static String format(int blocks) {
    return blocks < 0 ? "Server default" : blocks + " blocks";
  }
}
