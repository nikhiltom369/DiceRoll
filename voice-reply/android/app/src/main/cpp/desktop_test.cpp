// Desktop test for the chat engine (runs in CI, not part of the app).
// Usage: llm_test model.gguf system_prompt.txt "German sentence 1" "German sentence 2" ...
// All sentences are one conversation; a second, separate conversation checks reset().
// system_prompt.txt is the same file the app uses (src/main/assets).

#include "llm_core.h"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>

int main(int argc, char ** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s model.gguf system_prompt.txt \"message\" [\"message\" ...]\n", argv[0]);
        return 2;
    }
    std::ifstream prompt_file(argv[2]);
    std::stringstream prompt_buf;
    prompt_buf << prompt_file.rdbuf();
    const std::string SYSTEM_PROMPT = prompt_buf.str();
    if (SYSTEM_PROMPT.empty()) {
        fprintf(stderr, "FAILED: could not read %s\n", argv[2]);
        return 2;
    }
    llm_global_init(nullptr);

    auto t0 = std::chrono::steady_clock::now();
    LlmSession * s = llm_load(argv[1], 2048, 4);
    if (s == nullptr) {
        fprintf(stderr, "FAILED to load model\n");
        return 1;
    }
    auto ms = [](auto a) {
        return (long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - a).count();
    };
    printf("model loaded in %ld ms\n", ms(t0));

    try {
        llm_reset(s, SYSTEM_PROMPT);
        for (int i = 3; i < argc; ++i) {
            auto t = std::chrono::steady_clock::now();
            std::string reply = llm_reply(s, argv[i], 200);
            printf("\nUSER:  %s\nREPLY: %s\n(%ld ms)\n", argv[i], reply.c_str(), ms(t));
            if (reply.empty()) {
                fprintf(stderr, "FAILED: empty reply\n");
                return 1;
            }
        }
        // New conversation must not remember the old one.
        llm_reset(s, SYSTEM_PROMPT);
        std::string reply = llm_reply(s, "Worüber haben wir gerade gesprochen?", 200);
        printf("\n[after reset]\nUSER:  Worüber haben wir gerade gesprochen?\nREPLY: %s\n", reply.c_str());
    } catch (const std::exception & e) {
        fprintf(stderr, "FAILED: %s\n", e.what());
        return 1;
    }

    llm_free(s);
    printf("\nOK\n");
    return 0;
}
