// Binds pthread_* functions to scalanative_pthread_* functions.
// This allows GCs to hook into pthreads.
// Every GC must include this file.

#include <pthread.h>

int scalanative_pthread_create(pthread_t *thread, const pthread_attr_t *attr,
                               void *(*start_routine)(void *), void *arg) {
    return pthread_create(thread, attr, start_routine, arg);
}

int scalanative_pthread_join(pthread_t thread, void **value_ptr) {
    return pthread_join(thread, value_ptr);
}

int scalanative_pthread_detach(pthread_t thread) {
    return pthread_detach(thread);
}

int scalanative_pthread_cancel(pthread_t thread) {
    return pthread_cancel(thread);
}

int scalanative_pthread_cond_wait(pthread_cond_t *cond, pthread_mutex_t *mutex) {
    return pthread_cond_wait(cond, mutex);
}

int scalanative_pthread_cond_timedwait(pthread_cond_t *cond, pthread_mutex_t *mutex, const struct timespec *abstime) {
    return pthread_cond_timedwait(cond, mutex, abstime);
}

void scalanative_pthread_exit(void *retval) { pthread_exit(retval); }

// not bound in scala-native
/*
int scalanative_pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset){
    return  pthread_sigmask(how, set, oldset);
}*/
