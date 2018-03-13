#include <stdlib.h>
#include <sys/mman.h>
#include <pthread.h>
#include <stdatomic.h>

// Darwin defines MAP_ANON instead of MAP_ANONYMOUS
#if !defined(MAP_ANONYMOUS) && defined(MAP_ANON)
#define MAP_ANONYMOUS MAP_ANON
#endif

// Dummy GC that maps chunks of 512MB per thread and allocates but never frees.

// Chunk size of 512MB
#define CHUNK (512 * 1024 * 1024L)
// Allow read and write
#define DUMMY_GC_PROT (PROT_READ | PROT_WRITE)
// Map private anonymous memory, and prevent from reserving swap
#define DUMMY_GC_FLAGS (MAP_NORESERVE | MAP_PRIVATE | MAP_ANONYMOUS)
// Map anonymous memory (not a file)
#define DUMMY_GC_FD -1
#define DUMMY_GC_FD_OFFSET 0

// use char pointers to allow easier pointer arithmetic
typedef struct {
    char* current;
    char* end;
} thread_heap;


// thread local variable(see the C11 specs)
_Thread_local thread_heap heap = {.current = NULL, .end = NULL};


// allocates the thread heap
static void initialize_memory() {
    heap.current = mmap(NULL, CHUNK, DUMMY_GC_PROT, DUMMY_GC_FLAGS, DUMMY_GC_FD,
                   DUMMY_GC_FD_OFFSET);
    heap.end = heap.current + CHUNK;
}


// called by main thread
void scalanative_init() {
    initialize_memory();
}

void *scalanative_alloc(void *info, size_t size) {
    size = size + (8 - size % 8);

    // check if the thread has enough space
    if (heap.current + size < heap.end) {
        void **alloc = (void*)heap.current;
        *alloc = info;
        heap.current += size;
        return alloc;
    } else {
        // allocate new space
        initialize_memory();
        // call again alloc, this time it surely suceedes
        return scalanative_alloc(info, size);
    }

}

void *scalanative_alloc_small(void *info, size_t size) {
    return scalanative_alloc(info, size);
}

void *scalanative_alloc_large(void *info, size_t size) {
    return scalanative_alloc(info, size);
}

void *scalanative_alloc_atomic(void *info, size_t size) {
    return scalanative_alloc(info, size);
}

void scalanative_collect() {}
