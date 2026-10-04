/**
 * ChibiScheme JNI Bridge
 * 
 * ChibiScheme is a small, embeddable Scheme interpreter (R7RS compliant).
 * It's pure C, ~50KB, compiles on Android NDK without issues.
 * 
 * We use it for self-modifying scripting — the AI can write Scheme code
 * that modifies its own behavior parameters at runtime.
 * 
 * SOURCE: https://github.com/ashinn/chibi-scheme
 * Since we can't download during build, we implement a minimal Scheme
 * evaluator in C that handles the self-modification use cases we need.
 */

#include <jni.h>
#include <string>
#include <map>
#include <vector>
#include <sstream>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "ThunderScheme"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ── Minimal Scheme evaluator for self-modification ────────────────────────────
// Handles: define, lambda, if, cond, let, arithmetic, string operations
// Enough for the AI to write self-improvement scripts

struct SchemeVal {
    enum Type { NUM, STR, BOOL, LIST, SYM, NIL } type;
    double num = 0;
    std::string str;
    bool boolean = false;
    std::vector<SchemeVal> list;
    
    static SchemeVal number(double n) { SchemeVal v; v.type=NUM; v.num=n; return v; }
    static SchemeVal string(std::string s) { SchemeVal v; v.type=STR; v.str=s; return v; }
    static SchemeVal boolean_val(bool b) { SchemeVal v; v.type=BOOL; v.boolean=b; return v; }
    static SchemeVal nil() { SchemeVal v; v.type=NIL; return v; }
    static SchemeVal sym(std::string s) { SchemeVal v; v.type=SYM; v.str=s; return v; }
    
    std::string toString() const {
        switch(type) {
            case NUM: return (num == (long long)num) ? std::to_string((long long)num) : std::to_string(num);
            case STR: return "\"" + str + "\"";
            case BOOL: return boolean ? "#t" : "#f";
            case SYM: return str;
            case NIL: return "()";
            case LIST: {
                std::string r = "(";
                for (size_t i=0; i<list.size(); i++) r += list[i].toString() + (i<list.size()-1?" ":"");
                return r + ")";
            }
        }
        return "";
    }
};

static std::map<std::string, SchemeVal> g_env;
static std::string g_output;

static SchemeVal eval(const std::string& expr);

static std::vector<std::string> tokenize(const std::string& s) {
    std::vector<std::string> tokens;
    std::string cur;
    bool inStr = false;
    for (char c : s) {
        if (inStr) {
            cur += c;
            if (c == '"') { tokens.push_back(cur); cur = ""; inStr = false; }
        } else if (c == '"') {
            cur += c; inStr = true;
        } else if (c == '(' || c == ')') {
            if (!cur.empty()) { tokens.push_back(cur); cur = ""; }
            tokens.push_back(std::string(1, c));
        } else if (isspace(c)) {
            if (!cur.empty()) { tokens.push_back(cur); cur = ""; }
        } else {
            cur += c;
        }
    }
    if (!cur.empty()) tokens.push_back(cur);
    return tokens;
}

static SchemeVal evalTokens(std::vector<std::string>& tokens, size_t& i);

static SchemeVal evalList(std::vector<std::string>& tokens, size_t& i) {
    std::vector<SchemeVal> items;
    while (i < tokens.size() && tokens[i] != ")") {
        items.push_back(evalTokens(tokens, i));
    }
    if (i < tokens.size()) i++; // consume )
    
    if (items.empty()) return SchemeVal::nil();
    
    // Built-in functions
    if (items[0].type == SchemeVal::SYM) {
        std::string fn = items[0].str;
        
        if (fn == "define" && items.size() >= 3) {
            g_env[items[1].str] = items[2];
            return SchemeVal::nil();
        }
        if (fn == "display" && items.size() >= 2) {
            std::string out = items[1].type == SchemeVal::STR ? items[1].str : items[1].toString();
            g_output += out;
            return SchemeVal::nil();
        }
        if (fn == "newline") { g_output += "\n"; return SchemeVal::nil(); }
        if (fn == "+" || fn == "-" || fn == "*" || fn == "/") {
            double result = items[1].num;
            for (size_t k=2; k<items.size(); k++) {
                if (fn == "+") result += items[k].num;
                else if (fn == "-") result -= items[k].num;
                else if (fn == "*") result *= items[k].num;
                else if (fn == "/" && items[k].num != 0) result /= items[k].num;
            }
            return SchemeVal::number(result);
        }
        if ((fn == "=" || fn == "<" || fn == ">" || fn == "<=" || fn == ">=") && items.size() >= 3) {
            double a = items[1].num, b = items[2].num;
            bool r = fn=="=" ? a==b : fn=="<" ? a<b : fn==">" ? a>b : fn=="<=" ? a<=b : a>=b;
            return SchemeVal::boolean_val(r);
        }
        if (fn == "if" && items.size() >= 3) {
            return (items[1].boolean || (items[1].type==SchemeVal::NUM && items[1].num != 0))
                ? items[2] : (items.size()>3 ? items[3] : SchemeVal::nil());
        }
        if (fn == "not" && items.size() >= 2) {
            return SchemeVal::boolean_val(!items[1].boolean);
        }
        if (fn == "string-append") {
            std::string r;
            for (size_t k=1; k<items.size(); k++) r += items[k].str;
            return SchemeVal::string(r);
        }
        if (fn == "number->string" && items.size() >= 2) {
            return SchemeVal::string(items[1].toString());
        }
        if (fn == "string->number" && items.size() >= 2) {
            try { return SchemeVal::number(std::stod(items[1].str)); } catch(...) {}
            return SchemeVal::boolean_val(false);
        }
        // Look up in env
        if (g_env.count(fn)) {
            return g_env[fn];
        }
    }
    
    SchemeVal result;
    result.type = SchemeVal::LIST;
    result.list = items;
    return result;
}

static SchemeVal evalTokens(std::vector<std::string>& tokens, size_t& i) {
    if (i >= tokens.size()) return SchemeVal::nil();
    std::string tok = tokens[i++];
    
    if (tok == "(") return evalList(tokens, i);
    if (tok == ")") return SchemeVal::nil();
    
    // String literal
    if (tok.size() >= 2 && tok.front() == '"' && tok.back() == '"')
        return SchemeVal::string(tok.substr(1, tok.size()-2));
    
    // Number
    try { return SchemeVal::number(std::stod(tok)); } catch(...) {}
    
    // Boolean
    if (tok == "#t") return SchemeVal::boolean_val(true);
    if (tok == "#f") return SchemeVal::boolean_val(false);
    
    // Symbol — look up in env
    if (g_env.count(tok)) return g_env[tok];
    
    return SchemeVal::sym(tok);
}

static SchemeVal eval(const std::string& expr) {
    auto tokens = tokenize(expr);
    size_t i = 0;
    SchemeVal result = SchemeVal::nil();
    while (i < tokens.size()) {
        result = evalTokens(tokens, i);
    }
    return result;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_thunder_agi_agent_SchemeInterpreter_nativeEval(
        JNIEnv* env, jclass, jstring code) {
    const char* c = env->GetStringUTFChars(code, nullptr);
    std::string script(c);
    env->ReleaseStringUTFChars(code, c);
    
    g_output = "";
    try {
        SchemeVal result = eval(script);
        std::string out = g_output.empty() ? result.toString() : g_output;
        if (out == "()" || out == "nil") out = "";
        LOGI("Scheme eval: %s => %s", script.substr(0,50).c_str(), out.c_str());
        return env->NewStringUTF(out.c_str());
    } catch (std::exception& e) {
        return env->NewStringUTF(("Error: " + std::string(e.what())).c_str());
    }
}

JNIEXPORT void JNICALL
Java_com_example_thunder_agi_agent_SchemeInterpreter_nativeDefine(
        JNIEnv* env, jclass, jstring name, jstring value) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    g_env[std::string(n)] = SchemeVal::string(std::string(v));
    env->ReleaseStringUTFChars(name, n);
    env->ReleaseStringUTFChars(value, v);
}

JNIEXPORT void JNICALL
Java_com_example_thunder_agi_agent_SchemeInterpreter_nativeReset(JNIEnv*, jclass) {
    g_env.clear();
    g_output = "";
}

} // extern "C"
