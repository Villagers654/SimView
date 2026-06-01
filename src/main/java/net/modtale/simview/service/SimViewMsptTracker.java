package net.modtale.simview.service;

import java.util.Arrays;

final class SimViewMsptTracker {

  private double[] samples;
  private double[] sortedSamples;
  private int collectionPeriodTicks;
  private int nextSampleIndex;
  private int size;

  SimViewMsptTracker(int collectionPeriodTicks) {
    this.collectionPeriodTicks = Math.max(1, collectionPeriodTicks);
    this.samples = new double[this.collectionPeriodTicks];
    this.sortedSamples = new double[this.collectionPeriodTicks];
  }

  void setCollectionPeriodTicks(int collectionPeriodTicks) {
    int nextPeriod = Math.max(1, collectionPeriodTicks);
    if (nextPeriod == this.collectionPeriodTicks) {
      return;
    }

    double[] previousSamples = samples;
    int previousSize = size;
    int previousNextSampleIndex = nextSampleIndex;

    this.collectionPeriodTicks = nextPeriod;
    this.samples = new double[nextPeriod];
    this.sortedSamples = new double[nextPeriod];
    this.nextSampleIndex = 0;
    this.size = 0;

    int samplesToKeep = Math.min(previousSize, nextPeriod);
    for (int i = samplesToKeep; i > 0; i--) {
      int sourceIndex = Math.floorMod(previousNextSampleIndex - i, previousSamples.length);
      addTickSample(previousSamples[sourceIndex]);
    }
  }

  void addTickSample(double tickDurationMs) {
    double sample = sanitizeTickDuration(tickDurationMs);
    if (size == samples.length) {
      removeSortedSample(samples[nextSampleIndex]);
    }

    samples[nextSampleIndex] = sample;
    insertSortedSample(sample);
    nextSampleIndex = (nextSampleIndex + 1) % samples.length;
  }

  double currentMspt() {
    if (size == 0) {
      return 50.0D;
    }

    int midpoint = size / 2;
    if ((size & 1) == 0) {
      return 0.5D * (sortedSamples[midpoint - 1] + sortedSamples[midpoint]);
    }
    return sortedSamples[midpoint];
  }

  void clear() {
    nextSampleIndex = 0;
    size = 0;
  }

  private void insertSortedSample(double sample) {
    int index = Arrays.binarySearch(sortedSamples, 0, size, sample);
    if (index < 0) {
      index = -index - 1;
    } else {
      while (index < size && sortedSamples[index] <= sample) {
        index++;
      }
    }
    if (index < size) {
      System.arraycopy(sortedSamples, index, sortedSamples, index + 1, size - index);
    }
    sortedSamples[index] = sample;
    size++;
  }

  private void removeSortedSample(double sample) {
    int index = Arrays.binarySearch(sortedSamples, 0, size, sample);
    if (index < 0) {
      return;
    }
    int itemsToMove = size - index - 1;
    if (itemsToMove > 0) {
      System.arraycopy(sortedSamples, index + 1, sortedSamples, index, itemsToMove);
    }
    size--;
  }

  private static double sanitizeTickDuration(double tickDurationMs) {
    return Double.isFinite(tickDurationMs) ? Math.max(0.0D, tickDurationMs) : 0.0D;
  }
}
