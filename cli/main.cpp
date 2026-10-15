#include "ipcam_linux.h"
#include "ipcam_pattern.hpp"
#include "ipcam_pipewire_sink.hpp"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <iostream>
#include <linux/videodev2.h>
#include <memory>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
volatile sig_atomic_t g_stop = 0;
void stop_handler(int) { g_stop = 1; }
namespace fs = std::filesystem;
using Clock = std::chrono::steady_clock;

struct Config {
    std::string input;
    std::string device = "auto";
    std::string backend = "pipewire";
    int width = 1280;
    int height = 720;
    int fps = 30;
    int rotation = 0;
    int max_frames = 0;
    int max_retries = 0; // 0 means unlimited retries.
    int stall_timeout = 12;
    bool dry_run = false;
    bool reconnect = true;
    bool looping = false;
    bool native_pattern = true;    // Camera-first: works without phone/FFmpeg.
    bool doctor = false;
    bool list_devices = false;
};

void usage() {
    std::cout
        << "IPCamera Pro Linux 0.2 - Virtual webcam for PCs without physical cameras\n\n"
        << "Usage:\n"
        << "  ipcam-cli                       Start PipeWire camera with native color bars\n"
        << "  ipcam-cli --input URL_OR_FILE   Send an IP stream, video or picture to camera\n"
        << "  ipcam-cli --doctor              Diagnose video device configuration\n"
        << "  ipcam-cli --list-devices        Inspect existing /dev/video* devices\n\n"
        << "Options:\n"
        << "  --backend pipewire|v4l2  Default: pipewire (no root)\n"
        << "  --device auto|/dev/videoN  V4L2 only\n"
        << "  --source pattern     Native animated test bars (default)\n"
        << "  --source stream      Use --input for file, HTTP(S), RTSP\n"
        << "  --input VALUE        Input URL, video path or still-image path\n"
        << "  --width N            Frame width: even, 2..4096 (default: 1280)\n"
        << "  --height N           Frame height: 2..4096 (default: 720)\n"
        << "  --fps N              1..120 (default: 30)\n"
        << "  --rotate N           0/90/180/270; stream sources only\n"
        << "  --frames N           Stop after N frames; 0 means indefinite\n"
        << "  --loop               Loop local video and image sources\n"
        << "  --no-reconnect       Disable automatic network stream retries\n"
        << "  --max-retries N      Stop after N failed reconnects; 0=unlimited\n"
        << "  --stall-timeout N    Restart stalled input after N seconds, 0=off\n"
        << "  --dry-run            Validate frames without opening a virtual device\n"
        << "  --test-pattern       Alias for --source pattern\n"
        << "  --help               Show this help\n\n"
        << "Default PipeWire backend needs no kernel module, MOK or Secure Boot changes.\n"
        << "V4L2 backend remains optional with --backend v4l2.\n";
}

int parse_int(const std::string& str, const char* name) {
    try {
        std::size_t pos = 0;
        const int n = std::stoi(str, &pos);
        if (pos != str.size()) throw std::invalid_argument("trailing characters");
        return n;
    } catch (const std::exception&) {
        throw std::invalid_argument(std::string("Invalid integer for ") + name + ": " + str);
    }
}

bool is_network(const std::string& input) {
    return input.rfind("http://", 0) == 0 || input.rfind("https://", 0) == 0 ||
           input.rfind("rtsp://", 0) == 0 || input.rfind("rtsps://", 0) == 0;
}

bool is_still(const std::string& file) {
    auto ext = fs::path(file).extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".bmp" || ext == ".webp";
}

Config parse_args(int argc, char** argv) {
    Config c;
    bool source_is_explicit_pattern = false;
    for (int i=1; i<argc; ++i) {
        const std::string opt(argv[i]);
        if (opt == "--help" || opt == "-h") { usage(); std::exit(0); }
        if (opt == "--doctor") { c.doctor=true; continue; }
        if (opt == "--list-devices") { c.list_devices=true; continue; }
        if (opt == "--test-pattern") { c.native_pattern=true; source_is_explicit_pattern=true; continue; }
        if (opt == "--dry-run") { c.dry_run=true; continue; }
        if (opt == "--loop") { c.looping=true; continue; }
        if (opt == "--no-reconnect") { c.reconnect=false; continue; }
        if (i+1 == argc) throw std::invalid_argument("Missing value for " + opt);
        const std::string val(argv[++i]);
        if (opt == "--source") {
            if (val == "pattern") { c.native_pattern=true; source_is_explicit_pattern=true; }
            else if (val == "stream") { c.native_pattern=false; source_is_explicit_pattern=false; }
            else throw std::invalid_argument("--source must be pattern or stream");
        } else if (opt == "--input") { c.input=val; c.native_pattern=false; }
        else if (opt == "--device") c.device=val;
        else if (opt == "--backend") c.backend=val;
        else if (opt == "--width") c.width=parse_int(val,"--width");
        else if (opt == "--height") c.height=parse_int(val,"--height");
        else if (opt == "--fps") c.fps=parse_int(val,"--fps");
        else if (opt == "--rotate") c.rotation=parse_int(val,"--rotate");
        else if (opt == "--frames") c.max_frames=parse_int(val,"--frames");
        else if (opt == "--max-retries") c.max_retries=parse_int(val,"--max-retries");
        else if (opt == "--stall-timeout") c.stall_timeout=parse_int(val,"--stall-timeout");
        else throw std::invalid_argument("Unknown argument: " + opt);
    }
    if (c.backend!="pipewire" && c.backend!="v4l2") throw std::invalid_argument("--backend must be pipewire or v4l2");
    if (c.doctor || c.list_devices) return c;
    if (c.width < 2 || c.width > 4096 || c.width%2 || c.height < 2 || c.height > 4096 ||
        c.fps < 1 || c.fps > 120 || c.max_frames < 0 || c.max_retries < 0 || c.max_retries > 100 || c.stall_timeout < 0 || c.stall_timeout > 3600 ||
        (c.rotation!=0 && c.rotation!=90 && c.rotation!=180 && c.rotation!=270)) {
        throw std::invalid_argument("Use even width 2..4096, height 2..4096, fps 1..120, rotate 0/90/180/270");
    }
    if (source_is_explicit_pattern && !c.input.empty())
        throw std::invalid_argument("Use --source pattern or --input, not both");
    if (!c.native_pattern && c.input.empty()) throw std::invalid_argument("--source stream requires --input");
    if (c.native_pattern && c.rotation) throw std::invalid_argument("--rotate is only supported for stream sources");
    if (!c.dry_run && c.backend=="pipewire" && c.device!="auto") throw std::invalid_argument("--device requires --backend v4l2");
    if (!c.dry_run && c.device != "auto") {
        if (c.device.rfind("/dev/video", 0) != 0 || c.device.size() <= 10 ||
            !std::all_of(c.device.begin()+10,c.device.end(),[](unsigned char ch){return ch>='0' && ch<='9';}))
            throw std::invalid_argument("Output must be auto or a /dev/videoN device (never a regular file)");
    }
    return c;
}

void show_devices() {
    std::cout << "V4L2 video devices detected:\n";
    int found = 0;
    std::error_code ec;
    if (!fs::exists("/dev",ec)) { std::cout << "  /dev unavailable\n"; return; }
    for (const auto& entry : fs::directory_iterator("/dev",ec)) {
        auto name = entry.path().filename().string();
        if (name.rfind("video",0)!=0 || name.size()<=5 ||
            !std::all_of(name.begin()+5,name.end(),[](unsigned char ch){return ch>='0' && ch<='9';})) continue;
        const auto path = entry.path().string();
        const int fd = ::open(path.c_str(),O_RDWR|O_NONBLOCK|O_CLOEXEC);
        std::cout << "  " << path;
        if (fd<0) { std::cout << " [unavailable: " << std::strerror(errno) << "]\n"; ++found; continue; }
        v4l2_capability cap{};
        if (::ioctl(fd,VIDIOC_QUERYCAP,&cap) == 0) {
            auto caps = (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
            std::cout << " [" << reinterpret_cast<const char*>(cap.card) << "] "
                      << ((caps & V4L2_CAP_VIDEO_OUTPUT) ? "OUTPUT (can feed)" :
                          (caps & V4L2_CAP_VIDEO_CAPTURE) ? "CAPTURE (can read)" : "other");
        } else std::cout << " [not V4L2]";
        std::cout << "\n";
        ::close(fd);
        ++found;
    }
    if (found==0) std::cout << "  No V4L2 devices found (normal when using PipeWire).\n";
}

void doctor(const std::string& path) {
    const std::string socket="/run/user/"+std::to_string(::getuid())+"/pipewire-0";
    std::cout << "PipeWire socket: " << (fs::exists(socket)?"FOUND":"MISSING") << "\n";
    std::cout << "IPCamera Pro | Linux virtual-camera diagnostic\n";
    std::cout << "Kernel v4l2loopback: " << (fs::exists("/sys/module/v4l2loopback")? "LOADED" : "NOT LOADED") << "\n";
    if (path == "auto") {
        char resolved[256]{};
        if (ipcam_find_virtual_device(resolved, sizeof(resolved)) == 0)
            std::cout << "IPCamera Pro virtual camera: " << resolved << " (FOUND)\n";
        else
            std::cout << "IPCamera Pro virtual camera: NOT FOUND (" << ipcam_last_error() << ")\n";
    } else {
        std::cout << "Requested virtual camera " << path << ": "
                  << (fs::exists(path)? "EXISTS" : "MISSING") << "\n";
    }
    std::cout << "Device ownership/access is shown below:\n";
    show_devices();
    std::cout << "Note: exclusive_caps=1 can hide the camera from apps until producer starts.\n";
    std::cout << "Default: run ipcam-cli with PipeWire; v4l2loopback setup is only for the legacy backend.\n";
}

struct Child {
    pid_t pid=-1;
    int fd=-1;
    Child() = default;
    Child(const Child&) = delete;
    Child& operator=(const Child&)=delete;
    Child(Child&& rhs) noexcept :pid(rhs.pid),fd(rhs.fd) {rhs.pid=-1;rhs.fd=-1;}
    Child& operator=(Child&& rhs) noexcept {
        if (this!=&rhs) { stop(); pid=rhs.pid;fd=rhs.fd;rhs.pid=-1;rhs.fd=-1; }
        return *this;
    }
    ~Child(){stop();}
    void stop() {
        // Signal FFmpeg before closing its output pipe, so a normal user stop
        // does not produce an alarming (but harmless) "Broken pipe" message.
        if (pid>0) {
            ::kill(pid,SIGTERM);
            int status=0;
            bool done=false;
            for (int i=0;i<20;++i) {
                const auto res=::waitpid(pid,&status,WNOHANG);
                if (res==pid || (res<0 && errno==ECHILD)) {done=true;break;}
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
            if (!done) {
                ::kill(pid,SIGKILL);
                while (::waitpid(pid,&status,0)<0 && errno==EINTR) {}
            }
            pid=-1;
        }
        if (fd>=0) { ::close(fd); fd=-1; }
    }
};

std::vector<std::string> ffmpeg_args(const Config& c) {
    // FFmpeg echoes the input URL on HTTP failures. Hide private bearer URLs.
    // Initial HTTP auth diagnostics are handled by phone-preflight.py.
    const bool phone_secret = c.input.find("?token=") != std::string::npos ||
        c.input.find("&token=") != std::string::npos;
    std::vector<std::string> args = {"ffmpeg","-hide_banner","-nostdin","-nostats",
                                     "-loglevel", phone_secret ? "quiet" : "error"};
    const bool network = is_network(c.input);
    if (network) {
        // Low-latency MJPEG/RTSP intake: the default probe buffer (5 MiB)
        // delays the first frame by seconds on a ~40 KB/frame phone stream
        // and shows up for the user as a long black camera image.
        args.insert(args.end(), {"-fflags","nobuffer","-max_delay","0",
                                 "-analyzeduration","0","-probesize","500000"});
    }
    if (c.input.rfind("rtsp://",0)==0 || c.input.rfind("rtsps://",0)==0)
        args.insert(args.end(),{"-rtsp_transport","tcp"});
    if (!network) {
        if (is_still(c.input)) args.insert(args.end(),{"-loop","1","-framerate",std::to_string(c.fps)});
        else if (c.looping) args.insert(args.end(),{"-stream_loop","-1"});
        args.push_back("-re"); // Don't flood virtual camera when reading a local file.
    }
    args.insert(args.end(),{"-i",c.input});
    std::string vf;
    if (c.rotation==90) vf="transpose=clock,";
    if (c.rotation==180) vf="hflip,vflip,";
    if (c.rotation==270) vf="transpose=cclock,";
    vf += "fps="+std::to_string(c.fps)+",scale="+std::to_string(c.width)+":"+std::to_string(c.height)+
          ":force_original_aspect_ratio=decrease,pad="+std::to_string(c.width)+":"+
          std::to_string(c.height)+":(ow-iw)/2:(oh-ih)/2,format=yuyv422";
    args.insert(args.end(),{"-map","0:v:0","-an","-sn","-dn","-vf",vf,
                            "-c:v","rawvideo","-pix_fmt","yuyv422","-f","rawvideo","pipe:1"});
    return args;
}

Child start_ffmpeg(const Config& c) {
    int fds[2]={-1,-1};
    if (::pipe(fds)<0) throw std::runtime_error("pipe: "+std::string(std::strerror(errno)));
    auto args=ffmpeg_args(c);
    auto pid=::fork();
    if (pid<0) {::close(fds[0]);::close(fds[1]);throw std::runtime_error("fork: "+std::string(std::strerror(errno)));}
    if (pid==0) {
        ::close(fds[0]);
        if (::dup2(fds[1],STDOUT_FILENO)<0) _exit(127);
        ::close(fds[1]);
        std::vector<char*> av;
        for (const auto& s:args) av.push_back(const_cast<char*>(s.c_str()));
        av.push_back(nullptr);
        ::execvp(av[0],av.data());
        const char msg[]="Could not launch ffmpeg. Install the ffmpeg package.\n";
        (void)::write(STDERR_FILENO,msg,sizeof(msg)-1);
        _exit(127);
    }
    ::close(fds[1]);
    Child child;child.pid=pid;child.fd=fds[0];return child;
}

bool read_frame(int fd,std::vector<unsigned char>& frame,int stall_timeout) {
    std::size_t offset=0;
    const auto start=Clock::now();
    while (offset<frame.size() && !g_stop) {
        if (stall_timeout>0 && Clock::now()-start > std::chrono::seconds(stall_timeout))
            return false;
        pollfd pfd{fd,POLLIN|POLLHUP,0};
        int ready=::poll(&pfd,1,250);
        if (ready==0 || (ready<0 && errno==EINTR)) continue;
        if (ready<0) throw std::runtime_error("poll: "+std::string(std::strerror(errno)));
        const ssize_t n=::read(fd,frame.data()+offset,frame.size()-offset);
        if (n==0) return false;
        if (n<0 && errno==EINTR) continue;
        if (n<0) throw std::runtime_error("read ffmpeg: "+std::string(std::strerror(errno)));
        offset+=static_cast<std::size_t>(n);
    }
    return offset==frame.size();
}

using DevicePtr=std::unique_ptr<IpcamVideoDevice,decltype(&ipcam_device_close)>;

// Mean Y of a packed YUYV frame (luma sits at even byte offsets).
// Used to turn a silent all-black phone feed into an explicit diagnostic.
double mean_luma(const unsigned char* frame, std::size_t bytes) {
    double sum = 0;
    const std::size_t samples = bytes / 2;
    for (std::size_t i = 0; i < bytes; i += 2) sum += frame[i];
    return samples ? sum / samples : 0.0;
}

// Verify the PipeWire GStreamer plugin before any frame is produced.
// Without pipewire-gstreamer the sink child exits immediately and the
// failure previously surfaced only as a late, confusing EPIPE.
void require_pipewire_plugin() {
    pid_t pid = ::fork();
    if (pid == 0) {
        const int nullfd = ::open("/dev/null", O_WRONLY);
        if (nullfd >= 0) {
            ::dup2(nullfd, STDOUT_FILENO);
            ::dup2(nullfd, STDERR_FILENO);
            if (nullfd > STDERR_FILENO) ::close(nullfd);
        }
        execlp("gst-inspect-1.0", "gst-inspect-1.0", "pipewiresink", nullptr);
        _exit(127);
    }
    if (pid < 0) throw std::runtime_error("fork: " + std::string(std::strerror(errno)));
    int status = 0;
    while (::waitpid(pid, &status, 0) < 0 && errno == EINTR) { }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
        throw std::runtime_error(
            "pipewiresink bulunamadı. Kur: sudo dnf install pipewire-gstreamer gstreamer1-plugins-good "
            "(PipeWire kamera düğümü bu eklenti olmadan oluşturulamaz)");
}

void send_frame(DevicePtr& output,IpcamPipeWireSink* pw,const std::vector<unsigned char>& frame) {
    if (pw) { pw->write_frame(frame.data(),frame.size(),&g_stop); return; }
    if (output && ipcam_device_write(output.get(),frame.data(),frame.size())!=0)
        throw std::runtime_error(std::string("Cannot send video to virtual camera: ")+ipcam_last_error());
}

void show_progress(int count,int fps) {
    if (count==1 || count%(fps*5)==0) std::cout << "Frames sent: " << count << "\n";
}

bool send_pattern_frames(const Config& c,DevicePtr& output,IpcamPipeWireSink* pw,int& frame_total,int count) {
    auto next=Clock::now();
    for (int i=0;(count<0 || i<count) && !g_stop;++i) {
        const auto frame=ipcam::make_test_frame(c.width,c.height,static_cast<std::uint64_t>(frame_total));
        send_frame(output,pw,frame);
        ++frame_total;
        show_progress(frame_total,c.fps);
        if (c.max_frames && frame_total>=c.max_frames) return true;
        next += std::chrono::microseconds(1000000/c.fps);
        std::this_thread::sleep_until(next);
    }
    return false;
}

int run_camera(const Config& c) {
    DevicePtr output(nullptr,&ipcam_device_close);
    std::unique_ptr<IpcamPipeWireSink> pw;
    std::string device_path = c.backend=="pipewire" ? "PipeWire IPCamera-Pro" : c.device;
    if (!c.dry_run && c.backend=="pipewire") {
        const std::string socket="/run/user/"+std::to_string(::getuid())+"/pipewire-0";
        if (!fs::exists(socket)) throw std::runtime_error("PipeWire session socket missing");
        require_pipewire_plugin();
        pw=std::make_unique<IpcamPipeWireSink>(c.width,c.height,c.fps);
    } else if (!c.dry_run) {
        if (device_path == "auto") {
            char resolved[256]{};
            if (ipcam_find_virtual_device(resolved, sizeof(resolved)) != 0)
                throw std::runtime_error(std::string("Virtual camera setup failed: ") + ipcam_last_error() +
                    ". Run scripts/fedora-setup.sh --check/--load; no system device was changed automatically.");
            device_path = resolved;
        }
        output.reset(ipcam_device_open(device_path.c_str(),c.width,c.height,c.fps));
        if (!output) throw std::runtime_error(std::string("Virtual camera setup failed: ")+ipcam_last_error()+
            ". Ensure v4l2loopback is loaded, device is correct and current user has write access.");
    }
    std::cout << "IPCamera Pro | " << c.width << "x" << c.height << " @ " << c.fps
              << " fps | " << (c.dry_run?"DRY RUN":device_path) << " | "
              << (c.native_pattern?"native TEST PATTERN":"FFmpeg SOURCE") << "\n";
    std::cout << "Press Ctrl+C to end video. This device is a software virtual camera.\n";
    int sent=0;
    if (c.native_pattern) {
        (void)send_pattern_frames(c,output,pw.get(),sent,-1);
        std::cout << "Stopped after " << sent << " frames.\n";
        return 0;
    }

    std::vector<unsigned char> frame(static_cast<std::size_t>(c.width)*c.height*2);
    int failures=0;
    int dark_streak=0;
    const int dark_warn_frames=std::max(2*c.fps,10); // ~2 seconds of black input
    const bool live_input=is_network(c.input);
    while (!g_stop) {
        Child child=start_ffmpeg(c);
        int segment_frames=0;
        while (!g_stop && read_frame(child.fd,frame,c.stall_timeout)) {
            if (live_input) {
                const double luma=mean_luma(frame.data(),frame.size());
                // Limited-range video black is Y≈16, not 0; a real scene,
                // even a dark one, carries noise above ~25 mean luma.
                if (luma < 22.0) ++dark_streak; else dark_streak=0;
                if (dark_streak==dark_warn_frames
                        || (dark_streak>dark_warn_frames && dark_streak%(dark_warn_frames*5)==0)) {
                    std::cerr << "UYARI: Telefondan " << (dark_streak/c.fps)
                              << " saniyedir tamamen siyah kareler geliyor (ortalama parlaklık "
                              << luma << "/255).\n"
                              << "  Ağ ve Fedora arka ucu çalışıyor; sorun büyük olasılıkla telefon kamerasında.\n"
                              << "  Telefonda uygulama içi canlı önizleme siyahsa: lensi kontrol et, uygulamayı\n"
                              << "  kapatıp yeniden başlat; GSI/custom ROM'da kamera HAL'i siyah kare üretebilir.\n";
                }
            }
            send_frame(output,pw.get(),frame);
            ++sent;
            ++segment_frames;
            show_progress(sent,c.fps);
            if (c.max_frames && sent>=c.max_frames) {
                std::cout << "Frame target reached: " << sent << "\n";
                return 0;
            }
        }
        child.stop();
        if (g_stop) break;
        if (!live_input || !c.reconnect) {
            if (segment_frames==0) throw std::runtime_error("Input produced no video frames; check URL/file or ffmpeg logs");
            std::cout << "Local input finished after " << sent << " frames.\n";
            return 0;
        }
        failures = segment_frames ? 1 : std::min(failures+1,100);
        if (c.max_retries > 0 && failures >= c.max_retries) {
            throw std::runtime_error(
                "Stream failed repeatedly. Android may have a NEW token. "
                "Restart the phone stream and paste its latest URL.");
        }
        const int retry_seconds=std::min(failures,5);
        std::cerr << "Stream interrupted; displaying native standby bars while reconnecting ("
                  << retry_seconds << " s)...\n";
        if (send_pattern_frames(c,output,pw.get(),sent,retry_seconds*c.fps)) return 0;
    }
    std::cout << "Stopped after " << sent << " frames.\n";
    return 0;
}
} // namespace

int main(int argc,char** argv) {
    try {
        const Config c=parse_args(argc,argv);
        if (c.list_devices) {show_devices();return 0;}
        if (c.doctor) {doctor(c.device);return 0;}
        struct sigaction action{};
        action.sa_handler=stop_handler;
        sigemptyset(&action.sa_mask);
        sigaction(SIGINT,&action,nullptr);
        sigaction(SIGTERM,&action,nullptr);
        signal(SIGPIPE,SIG_IGN);
        return run_camera(c);
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << "\n";
        return 1;
    }
}
