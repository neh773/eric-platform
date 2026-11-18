//! Fixed-bucket histograms. The scan thread only does atomic adds.
//! p50 and p99 are the upper edge of the bucket that covers that rank.
//! The maximum is exact.

use std::sync::atomic::{AtomicU64, Ordering};

pub struct Histogram {
    edges: Vec<u64>,
    counts: Vec<AtomicU64>,
    total: AtomicU64,
    max: AtomicU64,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct Bucket {
    pub le_ns: u64,
    pub n: u64,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct HistogramSnap {
    pub count: u64,
    pub p50_ns: u64,
    pub p99_ns: u64,
    pub max_ns: u64,
    pub buckets: Vec<Bucket>,
}

impl Histogram {
    pub fn new() -> Self {
        let edges = edges_ns();
        let counts = edges.iter().map(|_| AtomicU64::new(0)).collect();
        Self { edges, counts, total: AtomicU64::new(0), max: AtomicU64::new(0) }
    }

    pub fn record(&self, value: u64) {
        let idx = self.edges.partition_point(|edge| *edge < value).min(self.edges.len() - 1);
        self.counts[idx].fetch_add(1, Ordering::Relaxed);
        self.total.fetch_add(1, Ordering::Relaxed);
        self.max.fetch_max(value, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> HistogramSnap {
        let mut buckets = Vec::new();
        let mut running = Vec::with_capacity(self.edges.len());
        for (edge, count) in self.edges.iter().zip(self.counts.iter()) {
            let n = count.load(Ordering::Relaxed);
            running.push(n);
            if n > 0 {
                buckets.push(Bucket { le_ns: *edge, n });
            }
        }
        let total = self.total.load(Ordering::Relaxed);
        HistogramSnap {
            count: total,
            p50_ns: percentile(&self.edges, &running, total, 50),
            p99_ns: percentile(&self.edges, &running, total, 99),
            max_ns: self.max.load(Ordering::Relaxed),
            buckets,
        }
    }
}

impl Default for Histogram {
    fn default() -> Self {
        Self::new()
    }
}

fn percentile(edges: &[u64], counts: &[u64], total: u64, pct: u64) -> u64 {
    if total == 0 {
        return 0;
    }
    let need = (total * pct + 99) / 100;
    let need = need.max(1);
    let mut acc = 0u64;
    for (edge, n) in edges.iter().zip(counts.iter()) {
        acc += *n;
        if acc >= need {
            return *edge;
        }
    }
    *edges.last().unwrap_or(&0)
}

/// 100 ns buckets through 100 µs, then 1 µs through 2 ms, then coarser edges.
fn edges_ns() -> Vec<u64> {
    let mut edges = Vec::with_capacity(4096);
    let mut ns = 100u64;
    while ns <= 100_000 {
        edges.push(ns);
        ns += 100;
    }
    let mut us = 101u64;
    while us <= 2_000 {
        edges.push(us * 1_000);
        us += 1;
    }
    for ms in [3u64, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000] {
        edges.push(ms * 1_000_000);
    }
    edges.push(u64::MAX);
    edges
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percentile_uses_the_bucket_upper_edge() {
        let h = Histogram::new();
        h.record(50);
        h.record(150);
        h.record(5_000);
        let snap = h.snapshot();
        assert_eq!(snap.count, 3);
        assert_eq!(snap.max_ns, 5_000);
        assert!(snap.p50_ns >= 150 && snap.p50_ns <= 200);
        assert!(snap.p99_ns >= 5_000);
    }

    #[test]
    fn empty_histogram_is_zero() {
        let snap = Histogram::new().snapshot();
        assert_eq!(snap.count, 0);
        assert_eq!(snap.p50_ns, 0);
        assert_eq!(snap.max_ns, 0);
    }
}
