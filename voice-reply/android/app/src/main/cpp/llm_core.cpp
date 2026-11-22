#include "llm_core.h"

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <cstdio>
#include <stdexcept>

namespace {

// Format the whole conversation with the model's own chat template.
std::string format_chat(const LlmSession * s, bool add_assistant_prefix) {
    const char * tmpl = llama_model_chat_template(s->model, nullptr);

    std::vector<llama_chat_message> msgs;
    msgs.reserve(s->messages.size());
    for (const auto & m : s->messages) {
        msgs.push_back({m.first.c_str(), m.second.c_str()});
    }

    std::vector<char> buf(4096);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant_prefix,
                                          buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant_prefix,
                                      buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        throw std::runtime_error("this model's chat template is not supported");
    }
    return std::string(buf.data(), n);
}

std::vector<llama_token> tokenize(const LlmSession * s, const std::string & text, bool add_bos) {
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_bos, true);
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_bos, true) < 0) {
        throw std::runtime_error("failed to tokenize the prompt");
    }
    return tokens;
}

std::string token_to_text(const llama_vocab * vocab, llama_token token) {
    std::string piece(64, '\0');
    int32_t n = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
    if (n < 0) {
        piece.resize(-n);
        n = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, false);
    }
    piece.resize(n < 0 ? 0 : n);
    return piece;
}

int32_t memory_used(const LlmSession * s) {
    return llama_memory_seq_pos_max(llama_get_memory(s->ctx), 0) + 1;
}

// Process tokens at the next free positions; logits only for the last one.
void process(LlmSession * s, const llama_token * tokens, int32_t n_tokens) {
    const llama_pos pos_0 = memory_used(s);
    llama_batch_ext_clear(s->batch);
    for (int32_t i = 0; i < n_tokens; ++i) {
        const int32_t idx = llama_batch_ext_add_token(s->batch, 0, tokens[i]);
        const llama_pos pos = pos_0 + i;
        llama_batch_ext_set_pos(s->batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(s->batch, n_tokens - 1, true);
    const int32_t ret = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch);
    if (ret != 0) {
        throw std::runtime_error("the model failed to process the text (code " + std::to_string(ret) + ")");
    }
}

void clear_memory(LlmSession * s) {
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->processed_text.clear();
}

}  // namespace

void llm_global_init(const char * backend_dir) {
    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level >= GGML_LOG_LEVEL_ERROR) {
            fprintf(stderr, "%s", text);
        }
    }, nullptr);
    if (backend_dir != nullptr) {
        ggml_backend_load_all_from_path(backend_dir);
    } else {
        ggml_backend_load_all();
    }
    llama_backend_init();
}

LlmSession * llm_load(const std::string & model_path, int n_ctx, int n_threads) {
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU only: works on every phone
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        return nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx;
    cparams.n_batch         = n_ctx;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto * s    = new LlmSession();
    s->model    = model;
    s->ctx      = ctx;
    s->sampler  = smpl;
    s->batch    = llama_batch_ext_init(ctx);
    return s;
}

void llm_reset(LlmSession * s, const std::string & system_prompt) {
    s->messages.clear();
    if (!system_prompt.empty()) {
        s->messages.emplace_back("system", system_prompt);
    }
    clear_memory(s);
    llama_sampler_reset(s->sampler);
}

std::string llm_reply(LlmSession * s, const std::string & user_text, int max_tokens) {
    const int32_t n_ctx = (int32_t) llama_n_ctx(s->ctx);
    s->messages.emplace_back("user", user_text);

    std::string prompt;
    std::vector<llama_token> tokens;
    while (true) {
        prompt = format_chat(s, true);

        // Reuse what is already in memory if the new prompt simply continues it.
        const bool can_continue = !s->processed_text.empty() &&
                                  prompt.compare(0, s->processed_text.size(), s->processed_text) == 0;
        if (!can_continue) {
            clear_memory(s);
        }
        const std::string todo = prompt.substr(s->processed_text.size());
        tokens = tokenize(s, todo, s->processed_text.empty());

        if (memory_used(s) + (int32_t) tokens.size() + max_tokens <= n_ctx) {
            break;
        }
        // Too long: forget the oldest exchange (keep the system prompt) and start over.
        const size_t first = (!s->messages.empty() && s->messages[0].first == "system") ? 1 : 0;
        if (s->messages.size() - first <= 1) {
            s->messages.pop_back();
            throw std::runtime_error("the message is too long");
        }
        s->messages.erase(s->messages.begin() + first,
                          s->messages.begin() + std::min(first + 2, s->messages.size() - 1));
        clear_memory(s);
    }

    process(s, tokens.data(), (int32_t) tokens.size());
    s->processed_text = prompt;

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    std::string reply;
    for (int i = 0; i < max_tokens; ++i) {
        llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }
        const std::string piece = token_to_text(vocab, tok);
        reply += piece;
        s->processed_text += piece;
        process(s, &tok, 1);
    }

    s->messages.emplace_back("assistant", reply);
    return reply;
}

void llm_free(LlmSession * s) {
    if (s == nullptr) {
        return;
    }
    llama_batch_ext_free(s->batch);
    llama_sampler_free(s->sampler);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}
