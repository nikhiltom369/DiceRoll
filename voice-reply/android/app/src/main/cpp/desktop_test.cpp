// Desktop test for the chat engine (runs in CI, not part of the app).
// Usage: llm_test model.gguf "German sentence 1" "German sentence 2" ...
// All sentences are one conversation; a second, separate conversation checks reset().

#include "llm_core.h"

#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <string>

static const char * SYSTEM_PROMPT =
    "Du bist ein freundlicher Gesprächspartner. Der Nutzer spricht Deutsch mit dir, "
    "und deine Antwort wird laut vorgelesen. Antworte immer auf Deutsch, kurz und natürlich, "
    "meist in 1 bis 3 Sätzen. Benutze keine Emojis, keine Listen und kein Markdown.";

int main(int argc, char ** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s model.gguf \"message\" [\"message\" ...]\n", argv[0]);
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
        for (int i = 2; i < argc; ++i) {
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
