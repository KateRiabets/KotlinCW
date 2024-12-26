
#include <iostream>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <portaudio.h>
#include <opencv2/opencv.hpp>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
}

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "portaudio_x64.lib")
#pragma comment(lib, "avcodec.lib")
#pragma comment(lib, "avformat.lib")
#pragma comment(lib, "avutil.lib")
#pragma comment(lib, "swscale.lib")
#pragma comment(lib, "opencv_world4100.lib")

#define VIDEO_PORT 8090
#define MDNS_PORT 8091
#define BUFFER_SIZE 65535
#define AUDIO_RATE 44100
#define AUDIO_CHANNELS 1
#define AUDIO_FORMAT paInt16

DWORD WINAPI mdns_thread(LPVOID lpParam) noexcept;
void handle_client(SOCKET server_socket, PaStream* audio_stream);

int findDeviceIndex(const std::string& namePart) {
    int numDevices = Pa_GetDeviceCount();
    if (numDevices < 0) {
        std::cerr << "PortAudio: ошибка при получении количества устройств: "
            << numDevices << std::endl;
        return -1;
    }
    for (int i = 0; i < numDevices; ++i) {
        const PaDeviceInfo* deviceInfo = Pa_GetDeviceInfo(i);
        if (deviceInfo) {
            std::string devName = deviceInfo->name ? deviceInfo->name : "";
            if (devName.find(namePart) != std::string::npos) {
                return i;
            }
        }
    }
    return -1;
}

void handle_audio_data(const uint8_t* audio_data, size_t data_size, PaStream* audio_stream) {
    size_t numSamples = data_size / sizeof(int16_t);
    std::vector<int16_t> buffer(numSamples);
    std::memcpy(buffer.data(), audio_data, data_size);
    for (size_t i = 0; i < numSamples; i++) {
        buffer[i] = static_cast<int16_t>(buffer[i] * 0.5f);
    }
    if (Pa_IsStreamActive(audio_stream) == 1) {
        Pa_WriteStream(audio_stream, buffer.data(), numSamples);
    }
}

void rotate_frame(cv::Mat& frame, int angle) {
    angle = (angle % 360 + 360) % 360;
    if (angle == 90) {
        cv::rotate(frame, frame, cv::ROTATE_90_CLOCKWISE);
    }
    else if (angle == 180) {
        cv::rotate(frame, frame, cv::ROTATE_180);
    }
    else if (angle == 270) {
        cv::rotate(frame, frame, cv::ROTATE_90_COUNTERCLOCKWISE);
    }
}

DWORD WINAPI mdns_thread(LPVOID lpParam) noexcept {
    SOCKET mdns_socket = *(SOCKET*)lpParam;
    char buffer[BUFFER_SIZE];
    sockaddr_in client_address;
    int client_address_size = sizeof(client_address);
    while (true) {
        int bytes_received = recvfrom(mdns_socket, buffer, BUFFER_SIZE, 0,
            (sockaddr*)&client_address, &client_address_size);
        if (bytes_received == SOCKET_ERROR) {
            std::cerr << "Failed to receive mDNS request. Error Code: "
                << WSAGetLastError() << std::endl;
            continue;
        }
        char client_ip[INET_ADDRSTRLEN];
        InetNtopA(AF_INET, &client_address.sin_addr, client_ip, INET_ADDRSTRLEN);
        std::cout << "mDNS request received from " << client_ip << std::endl;
        std::string request(buffer, bytes_received);
        if (request.find("LocateHOST") != std::string::npos) {
            std::string response = "HOST_FOUND: " + std::string(client_ip)
                + ":" + std::to_string(VIDEO_PORT);
            int send_result = sendto(mdns_socket, response.c_str(),
                static_cast<int>(response.size()),
                0, (sockaddr*)&client_address,
                sizeof(client_address));
            if (send_result == SOCKET_ERROR) {
                std::cerr << "Failed to send mDNS response. Error Code: "
                    << WSAGetLastError() << std::endl;
            }
            else {
                std::cout << "mDNS response sent to " << client_ip << ":"
                    << ntohs(client_address.sin_port)
                    << " - " << response << std::endl;
            }
        }
    }
    return 0;
}

void handle_client(SOCKET server_socket, PaStream* audio_stream) {
    avformat_network_init();
    const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) {
        std::cerr << "Error: Could not find H.264 decoder!" << std::endl;
        return;
    }
    AVCodecContext* codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx) {
        std::cerr << "Error: Could not allocate codec context!" << std::endl;
        return;
    }
    if (avcodec_open2(codec_ctx, codec, nullptr) < 0) {
        std::cerr << "Error: Could not open codec!" << std::endl;
        avcodec_free_context(&codec_ctx);
        return;
    }
    AVFrame* frame = av_frame_alloc();
    AVPacket* packet = av_packet_alloc();
    if (!frame || !packet) {
        std::cerr << "Error: Could not allocate frame or packet!" << std::endl;
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&codec_ctx);
        return;
    }
    SwsContext* sws_ctx = nullptr;
    cv::Mat img;
    sockaddr_in client_address;
    int client_address_size = sizeof(client_address);
    char buffer[BUFFER_SIZE];
    int prev_width = 0, prev_height = 0;
    int rotation_angle = 0;
    bool flip_video = false;
    const int FIXED_WIDTH = 480;
    const int FIXED_HEIGHT = 640;
    try {
        while (true) {
            int bytes_received = recvfrom(
                server_socket, buffer, BUFFER_SIZE, 0,
                (sockaddr*)&client_address, &client_address_size
            );
            if (bytes_received == SOCKET_ERROR) {
                std::cerr << "Failed to receive data. Error Code: "
                    << WSAGetLastError() << std::endl;
                continue;
            }
            if (bytes_received <= 0) {
                continue;
            }
            uint8_t packet_type = static_cast<uint8_t>(buffer[0]);
            if (packet_type == 0x00) {
                packet->data = reinterpret_cast<uint8_t*>(buffer + 1);
                packet->size = bytes_received - 1;
                if (avcodec_send_packet(codec_ctx, packet) >= 0) {
                    while (avcodec_receive_frame(codec_ctx, frame) >= 0) {
                        if (frame->width != prev_width || frame->height != prev_height) {
                            std::cout << "Resolution changed: "
                                << frame->width << "x" << frame->height << std::endl;
                            prev_width = frame->width;
                            prev_height = frame->height;
                            if (sws_ctx) {
                                sws_freeContext(sws_ctx);
                                sws_ctx = nullptr;
                            }
                            sws_ctx = sws_getContext(
                                frame->width, frame->height, (AVPixelFormat)frame->format,
                                frame->width, frame->height, AV_PIX_FMT_BGR24,
                                SWS_BILINEAR, nullptr, nullptr, nullptr
                            );
                            if (!sws_ctx) {
                                std::cerr << "Error: Could not initialize sws_ctx!"
                                    << std::endl;
                                throw std::runtime_error("Failed to initialize SwsContext.");
                            }
                            img = cv::Mat(frame->height, frame->width, CV_8UC3);
                        }
                        uint8_t* cv_data[1] = { img.data };
                        int cv_linesize[1] = { static_cast<int>(img.step) };
                        sws_scale(
                            sws_ctx, frame->data, frame->linesize, 0, frame->height,
                            cv_data, cv_linesize
                        );
                        cv::Mat rotated_img = img.clone();
                        rotate_frame(rotated_img, rotation_angle);
                        if (flip_video) {
                            cv::flip(rotated_img, rotated_img, 1);
                        }
                        int display_width = FIXED_WIDTH;
                        int display_height = FIXED_HEIGHT;
                        if (rotation_angle == 90 || rotation_angle == 270) {
                            std::swap(display_width, display_height);
                        }
                        cv::Mat resized_img;
                        cv::resize(rotated_img, resized_img,
                            cv::Size(display_width, display_height),
                            0, 0, cv::INTER_LINEAR);
                        cv::imshow("Video Stream", resized_img);
                        int key = cv::waitKey(1);
                        if (key == 27) {
                            std::cout << "Exiting video display." << std::endl;
                            cv::destroyAllWindows();
                            return;
                        }
                        else if (key == 'a') {
                            rotation_angle -= 90;
                            rotation_angle = (rotation_angle % 360 + 360) % 360;
                        }
                        else if (key == 'd') {
                            rotation_angle += 90;
                            rotation_angle = (rotation_angle % 360 + 360) % 360;
                        }
                        else if (key == 'w') {
                            flip_video = !flip_video;
                        }
                    }
                }
            }
            else if (packet_type == 0x01) {
                handle_audio_data(
                    reinterpret_cast<uint8_t*>(buffer + 1),
                    bytes_received - 1,
                    audio_stream
                );
            }
        }
    }
    catch (std::exception& e) {
        std::cerr << "Error in handle_client: " << e.what() << std::endl;
    }
    if (sws_ctx) {
        sws_freeContext(sws_ctx);
    }
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&codec_ctx);
}

int main() {
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        std::cerr << "WSAStartup failed. Error Code: " << WSAGetLastError() << std::endl;
        return EXIT_FAILURE;
    }
    SOCKET mdns_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (mdns_socket == INVALID_SOCKET) {
        std::cerr << "Failed to create mDNS socket. Error Code: "
            << WSAGetLastError() << std::endl;
        WSACleanup();
        return EXIT_FAILURE;
    }
    BOOL opt_val = TRUE;
    if (setsockopt(mdns_socket, SOL_SOCKET, SO_BROADCAST,
        (char*)&opt_val, sizeof(opt_val)) == SOCKET_ERROR) {
        std::cerr << "Failed to set mDNS socket options. Error Code: "
            << WSAGetLastError() << std::endl;
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    sockaddr_in mdns_address = {};
    mdns_address.sin_family = AF_INET;
    mdns_address.sin_addr.s_addr = INADDR_ANY;
    mdns_address.sin_port = htons(MDNS_PORT);
    if (bind(mdns_socket, (sockaddr*)&mdns_address, sizeof(mdns_address)) == SOCKET_ERROR) {
        std::cerr << "Failed to bind mDNS socket. Error Code: "
            << WSAGetLastError() << std::endl;
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    std::cout << "mDNS socket bound to port " << MDNS_PORT << std::endl;
    CreateThread(nullptr, 0, mdns_thread, &mdns_socket, 0, nullptr);
    SOCKET server_socket = socket(AF_INET, SOCK_DGRAM, 0);
    if (server_socket == INVALID_SOCKET) {
        std::cerr << "Socket creation failed." << std::endl;
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    sockaddr_in server_address = {};
    server_address.sin_family = AF_INET;
    server_address.sin_addr.s_addr = INADDR_ANY;
    server_address.sin_port = htons(VIDEO_PORT);
    if (bind(server_socket, (sockaddr*)&server_address, sizeof(server_address))
        == SOCKET_ERROR) {
        std::cerr << "Failed to bind video socket. Error Code: "
            << WSAGetLastError() << std::endl;
        closesocket(server_socket);
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    std::cout << "Video server listening on port " << VIDEO_PORT << std::endl;
    PaError paErr = Pa_Initialize();
    if (paErr != paNoError) {
        std::cerr << "Failed to initialize PortAudio. Error: "
            << Pa_GetErrorText(paErr) << std::endl;
        closesocket(server_socket);
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    int cableDeviceIndex = findDeviceIndex("CABLE Input");
    if (cableDeviceIndex == -1) {
        std::cerr << "Не удалось найти устройство VB-Cable (CABLE Input)!"
            << std::endl;
        Pa_Terminate();
        closesocket(server_socket);
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    std::cout << "Найдено устройство VB-Cable, индекс: " << cableDeviceIndex << std::endl;
    PaStreamParameters outputParams;
    outputParams.device = cableDeviceIndex;
    outputParams.channelCount = AUDIO_CHANNELS;
    outputParams.sampleFormat = AUDIO_FORMAT;
    outputParams.suggestedLatency = Pa_GetDeviceInfo(outputParams.device)->defaultLowOutputLatency;
    outputParams.hostApiSpecificStreamInfo = nullptr;
    PaStream* audio_stream = nullptr;
    paErr = Pa_OpenStream(
        &audio_stream,
        nullptr,
        &outputParams,
        AUDIO_RATE,
        1024,
        paClipOff,
        nullptr,
        nullptr
    );
    if (paErr != paNoError) {
        std::cerr << "Failed to open audio stream. Error: "
            << Pa_GetErrorText(paErr) << std::endl;
        Pa_Terminate();
        closesocket(server_socket);
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    paErr = Pa_StartStream(audio_stream);
    if (paErr != paNoError) {
        std::cerr << "Failed to start audio stream. Error: "
            << Pa_GetErrorText(paErr) << std::endl;
        Pa_CloseStream(audio_stream);
        Pa_Terminate();
        closesocket(server_socket);
        closesocket(mdns_socket);
        WSACleanup();
        return EXIT_FAILURE;
    }
    std::cout << "PortAudio stream запущен (вывод на CABLE Input). Громкость - 50%.\n";
    handle_client(server_socket, audio_stream);
    Pa_StopStream(audio_stream);
    Pa_CloseStream(audio_stream);
    Pa_Terminate();
    closesocket(server_socket);
    closesocket(mdns_socket);
    WSACleanup();
    return 0;
}
