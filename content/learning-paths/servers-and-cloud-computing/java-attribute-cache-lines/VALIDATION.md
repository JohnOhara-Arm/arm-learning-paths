# Analyzer validation

The companion tools were validated on an Arm jump host using OpenJDK 21.0.11.

The reduced golden fixture preserves the 24 object joins and original JOL
layouts from the Sunflow capture. `test-tools.sh` verifies:

- compilation of both analyzers, the pagemap dumper, and the HotSpot SA scanner;
- all 24 Sunflow object joins;
- the `BucketThread` to `KDTree` boundary classification at `0x8e001840`;
- a synthetic physical-address join across pagemap;
- quoted CSV field handling.

The full 8,264,273-object, 286 MB heap CSV was also scanned. The compiled
analyzer produced the expected 24 joins in 16.43 seconds with `-Xmx256m`; peak
resident memory was approximately 315 MiB. Source-file mode produced the same
result but retained additional compiler memory, so compiled mode is recommended
for large captures.
