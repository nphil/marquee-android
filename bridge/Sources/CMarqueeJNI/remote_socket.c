#include "marquee_jni.h"

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

static void configure(int fd, bool datagram) {
    int one = 1;
    struct timeval timeout = { .tv_sec = 3 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof timeout);
#if defined(SO_NOSIGPIPE)
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
    if (datagram) setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &one, sizeof one);
    else setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    fcntl(fd, F_SETFD, FD_CLOEXEC);
}

static bool dial(int fd, const struct addrinfo *address) {
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) return false;
    if (connect(fd, address->ai_addr, address->ai_addrlen) < 0) {
        if (errno != EINPROGRESS) return false;
        struct pollfd pending = { .fd = fd, .events = POLLOUT };
        if (poll(&pending, 1, 3000) <= 0) return false;
        int error = 0;
        socklen_t length = sizeof error;
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) < 0 || error) return false;
    }
    return fcntl(fd, F_SETFL, flags) >= 0;
}

int marquee_remote_connect(const char *host, uint16_t port, bool datagram) {
    if (!*host || !port) return -1;
    char service[6];
    snprintf(service, sizeof service, "%u", (unsigned)port);
    struct addrinfo hints = { .ai_family = AF_UNSPEC, .ai_socktype = datagram ? SOCK_DGRAM : SOCK_STREAM };
    struct addrinfo *addresses = NULL;
    if (getaddrinfo(host, service, &hints, &addresses) != 0) return -1;
    int connected = -1;
    for (const struct addrinfo *address = addresses; address; address = address->ai_next) {
        int fd = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
        if (fd < 0) continue;
        configure(fd, datagram);
        if (dial(fd, address)) {
            connected = fd;
            break;
        }
        close(fd);
    }
    freeaddrinfo(addresses);
    return connected;
}

#if !defined(__ANDROID__)
int marquee_remote_listener(uint16_t *port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    configure(fd, false);
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    socklen_t length = sizeof address;
    if (bind(fd, (struct sockaddr *)&address, length) < 0 || listen(fd, 4) < 0 ||
        getsockname(fd, (struct sockaddr *)&address, &length) < 0) {
        close(fd);
        return -1;
    }
    *port = ntohs(address.sin_port);
    return fd;
}

int marquee_remote_accept(int fd) {
    int peer = accept(fd, NULL, NULL);
    if (peer >= 0) configure(peer, false);
    return peer;
}
#endif

ptrdiff_t marquee_remote_read(int fd, uint8_t *bytes, size_t length) {
    ssize_t received;
    do { received = recv(fd, bytes, length, 0); } while (received < 0 && errno == EINTR);
    return received;
}

bool marquee_remote_send(int fd, const uint8_t *bytes, size_t length, bool datagram) {
    int flags = 0;
#ifdef MSG_NOSIGNAL
    flags = MSG_NOSIGNAL;
#endif
    while (length) {
        ssize_t sent = send(fd, bytes, length, flags);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return false;
        if (datagram) return (size_t)sent == length;
        length -= (size_t)sent;
        bytes += sent;
    }
    return true;
}

void marquee_remote_stop_reading(int fd) { shutdown(fd, SHUT_RD); }
void marquee_remote_shutdown(int fd) { shutdown(fd, SHUT_RDWR); }
void marquee_remote_close(int fd) { close(fd); }
