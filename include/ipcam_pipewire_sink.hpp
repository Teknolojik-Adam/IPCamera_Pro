#pragma once
// IPCamera Pro: userspace PipeWire camera publisher (no kernel module).
// Writes native packed YUYV frames to a GStreamer PipeWire video source.
#include <cerrno>
#include <csignal>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

class IpcamPipeWireSink {
    pid_t child_ = -1;
    int fd_ = -1;

    static std::string error(const char* msg) {
        return std::string(msg) + ": " + std::strerror(errno);
    }
public:
    IpcamPipeWireSink(int width, int height, int fps) {
        // The plugin is installed with Fedora's pipewire-gstreamer package.
        int p[2] = {-1,-1};
        if (::pipe2(p, O_CLOEXEC) != 0) throw std::runtime_error(error("pipe2"));
        const std::vector<std::string> args = {
            "gst-launch-1.0","-q",
            "fdsrc","fd=0","is-live=true","do-timestamp=true","blocksize="+std::to_string(width*height*2),
            "!","rawvideoparse","format=yuy2","width="+std::to_string(width),
            "height="+std::to_string(height),"framerate="+std::to_string(fps)+"/1",
            "!","queue","max-size-buffers=2","max-size-bytes=0","max-size-time=0","leaky=downstream",
            "!","videoconvert","!","video/x-raw,format=I420",
            // KDE Kamoso can renegotiate/remove PipeWire buffers when it
            // connects or closes. The sink's default video bufferpool then
            // errors with "all buffers have been removed", terminating our
            // entire producer. Without the Gst bufferpool the Video/Source
            // survives Kamoso open/close (tested over 3 cycles / 310 frames).
            "!","pipewiresink","mode=provide","use-bufferpool=false","client-name=IPCamera-Pro",
            "stream-properties=properties,media.class=Video/Source,media.role=Camera,node.name=ipcamera-pro,node.description=IPCamera-Pro"
        };
        child_ = ::fork();
        if (child_ < 0) {
            const auto detail=error("fork");
            ::close(p[0]); ::close(p[1]);
            throw std::runtime_error(detail);
        }
        if (child_ == 0) {
            ::close(p[1]);
            if (::dup2(p[0], STDIN_FILENO) < 0) _exit(127);
            ::close(p[0]);
            std::vector<char*> argv;
            for (const auto& a: args) argv.push_back(const_cast<char*>(a.c_str()));
            argv.push_back(nullptr);
            ::execvp(argv[0], argv.data());
            const char msg[]="IPCamera Pro: gst-launch-1.0 missing (install pipewire-gstreamer and gstreamer1)\n";
            (void)::write(STDERR_FILENO, msg, sizeof(msg)-1);
            _exit(127);
        }
        ::close(p[0]);
        fd_=p[1];
        int flags=::fcntl(fd_, F_GETFL, 0);
        if (flags >= 0) ::fcntl(fd_, F_SETFL, flags | O_NONBLOCK);
        // A broken pipeline (missing pipewiresink, plugin load failure)
        // makes gst-launch exit within milliseconds. Detect that here at
        // startup instead of failing later with a cryptic EPIPE mid-stream.
        std::this_thread::sleep_for(std::chrono::milliseconds(400));
        int status = 0;
        const pid_t res = ::waitpid(child_, &status, WNOHANG);
        if (res == child_) {
            child_ = -1;
            ::close(fd_);
            fd_ = -1;
            throw std::runtime_error(
                "gst-launch-1.0 başlar başlamaz kapandı; PipeWire kamera düğümü oluşturulamadı. "
                "Kontrol: gst-inspect-1.0 pipewiresink (paket: pipewire-gstreamer)");
        }
    }
    IpcamPipeWireSink(const IpcamPipeWireSink&)=delete;
    IpcamPipeWireSink& operator=(const IpcamPipeWireSink&)=delete;
    ~IpcamPipeWireSink() {
        if (fd_ >= 0) ::close(fd_);
        if (child_ > 0) {
            ::kill(child_, SIGTERM);
            int status=0;
            bool done=false;
            for (int i=0; i<20; ++i) {
                auto result=::waitpid(child_, &status, WNOHANG);
                if (result == child_ || (result < 0 && errno == ECHILD)) {done=true;break;}
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
            if (!done) {
                ::kill(child_, SIGKILL);
                while (::waitpid(child_,&status,0)<0 && errno==EINTR) {}
            }
        }
    }
    void write_frame(const unsigned char* data, std::size_t bytes, const volatile sig_atomic_t* stop) {
        if (fd_ < 0) throw std::runtime_error("PipeWire stream pipe closed");
        std::size_t pos=0;
        const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(10);
        while (pos<bytes) {
            if (stop && *stop) return;
            if (std::chrono::steady_clock::now()>deadline)
                throw std::runtime_error("PipeWire stream stalled for 10 seconds");
            const ssize_t n=::write(fd_,data+pos,bytes-pos);
            if (n>0) { pos+=static_cast<std::size_t>(n); continue; }
            if (n<0 && errno==EINTR) continue;
            if (n<0 && (errno==EAGAIN || errno==EWOULDBLOCK)) {
                pollfd out{fd_,POLLOUT,0};
                auto ready=::poll(&out,1,100);
                if (ready<0 && errno!=EINTR) throw std::runtime_error(error("PipeWire poll"));
                continue;
            }
            throw std::runtime_error(error("PipeWire sink stopped (check GStreamer stderr)"));
        }
    }
};
