// Small chat engine on top of llama.cpp, shared by the Android app (via JNI)
// and the desktop test that runs in CI.
#pragma once

#include <string>
#include <utility>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_batch_ext;

struct LlmSession {
    llama_model *     model   = nullptr;
    llama_context *   ctx     = nullptr;
    llama_sampler *   sampler = nullptr;
    llama_batch_ext * batch   = nullptr;

    // Conversation so far: {role, content}. The first entry is the system prompt.
    std::vector<std::pair<std::string, std::string>> messages;

    // Exact text that is already processed into the model's memory, so a new
    // turn only needs to process what was added since the last one.
    std::string processed_text;
};

// Call once per process. backend_dir: folder with the ggml CPU backend
// libraries (Android: nativeLibraryDir). nullptr = use built-in backends.
void llm_global_init(const char * backend_dir);

// Returns nullptr on failure.
LlmSession * llm_load(const std::string & model_path, int n_ctx, int n_threads);

// Start a new conversation with the given system prompt.
void llm_reset(LlmSession * s, const std::string & system_prompt);

// Adds the user's message, generates the assistant's reply, remembers both.
// Throws std::runtime_error on failure.
std::string llm_reply(LlmSession * s, const std::string & user_text, int max_tokens);

void llm_free(LlmSession * s);
