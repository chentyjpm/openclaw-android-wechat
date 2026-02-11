extern "C" {
// OpenCV mobile static libs reference this symbol, but current Android NDK
// OpenMP runtime does not export it. A no-op fallback keeps linking working.
void __kmpc_dispatch_deinit(void*, int*) {}
}
