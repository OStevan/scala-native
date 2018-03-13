#include <stdlib.h>
#include <sys/mman.h>
#include <pthread.h>
#include <stdatomic.h>

// Darwin defines MAP_ANON instead of MAP_ANONYMOUS
#if !defined(MAP_ANONYMOUS) && defined(MAP_ANON)
#define MAP_ANONYMOUS MAP_ANON
#endif

// Dummy GC that maps chunks of 4GB and allocates but never frees.

// Map 4GB
#define CHUNK (512 * 1024 * 1024L)
// Allow read and write
#define DUMMY_GC_PROT (PROT_READ | PROT_WRITE)
// Map private anonymous memory, and prevent from reserving swap
#define DUMMY_GC_FLAGS (MAP_NORESERVE | MAP_PRIVATE | MAP_ANONYMOUS)
// Map anonymous memory (not a file)
#define DUMMY_GC_FD -1
#define DUMMY_GC_FD_OFFSET 0

// new

// pthread thread-specific data key and guard to call once
pthread_key_t heap_key;
pthread_once_t call_once_guard = PTHREAD_ONCE_INIT;
// lock for mmap invocations
pthread_mutex_t chunk_alloc_mutex = PTHREAD_MUTEX_INITIALIZER;


typedef struct {
    void* current;
    void* end;
} thread_heap;

static void free_heap_descriptor(void* data) {
    free(data);
}

static void make_key() {
    (void) pthread_key_create(&heap_key, free_heap_descriptor);
}


// thread safe as mmap needs to be locked
static void initialize_memory(thread_heap* heap) {
    pthread_mutex_lock(&chunk_alloc_mutex);
    heap->current = mmap(NULL, CHUNK, DUMMY_GC_PROT, DUMMY_GC_FLAGS, DUMMY_GC_FD,
                   DUMMY_GC_FD_OFFSET);
    heap->end = heap->current + CHUNK;
    pthread_mutex_unlock(&chunk_alloc_mutex);
}

static thread_heap* retreive_heap() {
    thread_heap* heap;
    (void) pthread_once(&call_once_guard, make_key);

    // allocate heap for the invoking thread
    if ((heap = pthread_getspecific(heap_key)) == NULL) {
        heap = malloc(sizeof(thread_heap));
        pthread_setspecific(heap_key, heap);
        // initilize memory of the invoking thread
        initialize_memory(heap);
    }
    return heap;
}

void scalanative_init() {
    retreive_heap();
}

void *scalanative_alloc(void *info, size_t size) {
    // 
    thread_heap* heap = retreive_heap();
    size = size + (8 - size % 8);

    // check if the thread has enough space
    if (heap->current + size < heap->end) {
        void **alloc = heap->current;
        *alloc = info;
        heap->current += size;
        return alloc;
    } else {
        // allocate new space
        initialize_memory(heap);
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
