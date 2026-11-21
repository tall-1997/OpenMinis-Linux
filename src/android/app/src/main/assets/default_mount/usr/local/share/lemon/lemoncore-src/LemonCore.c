/* ============================================================
 * LemonCore v1.0 · 原生 MCP 内核核心（🅒 去 Java 化第一步）
 * 2026-09-27 小柠檬本体 · 授权：lemon-user
 * ------------------------------------------------------------
 * 纯 C，clang/NDK r30/aarch64 android24。
 * - fork-per-connection：崩溃隔离，单连接崩溃不拖垮内核
 * - 注册表：plugins 目录(*.json) + legacy tools 目录（薄壳模板，与 Java 内核同源）
 * - 上游桥：本地没有的工具（Java 编译内建类）转发 8788 Java 内核
 * - 共享内存指标：跨进程真实计数
 * - 协议：JSON-RPC 2.0 / MCP（protocolVersion 2025-06-18）
 * 编译：clang LemonCore.c -O2 -o lemoncore
 * 运行：./lemoncore 8791
 * ============================================================ */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <ctype.h>
#include <time.h>
#include <dirent.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <poll.h>
#include <regex.h>

/* ---------- 配置 ---------- */
static int  PORT        = 8791;
static const char *PLUGIN_DIR = "/data/adb/lemon_kernel/plugins";
static const char *LEGACY_DIR = "/data/adb/modules/LemonMCPRoot/lemon/tools";
static const char *UP_HOST    = "127.0.0.1";
static int  UP_PORT     = 8788;
static int  UP_ENABLED  = 1;
static int  UP_TIMEOUT  = 180000;   /* 上游桥超时：须大于 Java 侧工具超时(60~120s)，靠过载阀兜并发 */
static const char *PROTOCOL_VERSION = "2025-06-18";
static const char *CORE_NAME  = "小柠檬内核";
static const char *CORE_VER   = "1.4.0";
#define RELOAD_FLAG "/data/adb/lemon_core/.reload_req"
#define MAX_CHILDREN 128
#define MAX_BODY  (32u*1024*1024)
#define MAX_OUT   (4u*1024*1024)

/* ---------- 共享内存指标 ---------- */
typedef struct {
    volatile long conns, requests, calls, failures, upstream, start_ms;
} Metrics;
static Metrics *M = NULL;

/* SIGCHLD：不能设 SIG_IGN（否则 waitpid 拿不到状态、无法回收计数） */
static volatile sig_atomic_t g_chld = 0;
static void on_sigchld(int s){ (void)s; g_chld = 1; }

static long now_ms(void){
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC,&ts);
    return ts.tv_sec*1000L + ts.tv_nsec/1000000L;
}

/* ---------- 动态字符串缓冲 ---------- */
typedef struct { char *p; size_t len, cap; } SB;

static void sb_init(SB *s){ s->cap=8192; s->len=0; s->p=(char*)malloc(s->cap); s->p[0]=0; }
static void sb_grow(SB *s, size_t need){
    if(s->len+need+1 > s->cap){
        while(s->cap < s->len+need+1) s->cap *= 2;
        char *np = (char*)realloc(s->p, s->cap);
        if(!np){ static const char m[]="[小柠檬内核] OOM\n"; write(2,m,sizeof m-1); _exit(70); }
        s->p = np;
    }
}
static void sb_putn(SB *s, const char *d, size_t n){
    sb_grow(s,n); memcpy(s->p+s->len,d,n); s->len+=n; s->p[s->len]=0;
}
static void sb_put(SB *s, const char *d){ if(d) sb_putn(s,d,strlen(d)); }
static void sb_putc(SB *s, char c){ sb_grow(s,1); s->p[s->len]=c; s->len++; s->p[s->len]=0; }
static void sb_puti(SB *s, long v){ char b[32]; snprintf(b,sizeof b,"%ld",v); sb_put(s,b); }
static void sb_free(SB *s){ if(s->p) free(s->p); s->p=NULL; s->len=s->cap=0; }

/* JSON 字符串字面量输出（转义） */
static void sb_json_str(SB *s, const char *v){
    sb_putc(s,'"');
    if(v) for(; *v; v++){
        unsigned char c = (unsigned char)*v;
        switch(c){
            case '"':  sb_put(s,"\\\"");  break;
            case '\\': sb_put(s,"\\\\");  break;
            case '\n': sb_put(s,"\\n");   break;
            case '\r': sb_put(s,"\\r");   break;
            case '\t': sb_put(s,"\\t");   break;
            case '\b': sb_put(s,"\\b");   break;
            case '\f': sb_put(s,"\\f");   break;
            default:
                if(c < 0x20){ char b[8]; snprintf(b,sizeof b,"\\u%04x",c); sb_put(s,b); }
                else sb_putc(s,(char)c);
        }
    }
    sb_putc(s,'"');
}/* ============================================================
 * JSON 扫描器（嵌套感知的最小实现）
 * ============================================================ */
static const char *skip_ws(const char *p, const char *e){
    while(p<e && (*p==' '||*p=='\t'||*p=='\n'||*p=='\r')) p++;
    return p;
}

/* 跳过一个 JSON 值，返回值末尾之后的位置 */
static const char *skip_value(const char *p, const char *e){
    p = skip_ws(p,e);
    if(p>=e) return p;
    if(*p=='"'){
        p++;
        while(p<e){
            if(*p=='\\'){ p+=2; continue; }
            if(*p=='"'){ return p+1; }
            p++;
        }
        return p;
    }
    if(*p=='{' || *p=='['){
        char open=*p, close=(open=='{')?'}':']';
        int d=0;
        while(p<e){
            if(*p=='"'){
                p++;
                while(p<e){ if(*p=='\\'){p+=2;continue;} if(*p=='"')break; p++; }
                if(p<e) p++;
                continue;
            }
            if(*p==open) d++;
            else if(*p==close){ d--; p++; if(d==0) return p; continue; }
            p++;
        }
        return p;
    }
    /* 数字 / true / false / null */
    while(p<e && *p!=',' && *p!='}' && *p!=']' && *p!=' ' && *p!='\t' && *p!='\n' && *p!='\r') p++;
    return p;
}

/* 在对象第一层成员中找 key 的原始值（逐成员整值跳过，绝不误中嵌套键） */
static int json_find(const char *obj, const char *e, const char *key, const char **vs, size_t *vl){
    size_t kl = strlen(key);
    const char *p = skip_ws(obj,e);
    if(p<e && *p=='{') p++;
    while(p < e){
        p = skip_ws(p,e);
        if(p>=e || *p=='}') return 0;
        if(*p=='"'){
            const char *ks = ++p;
            while(p<e){ if(*p=='\\'){p+=2;continue;} if(*p=='"')break; p++; }
            size_t kl2 = (size_t)(p-ks);
            if(p<e) p++;
            p = skip_ws(p,e);
            if(p<e && *p==':'){
                p++;
                const char *v = skip_ws(p,e);
                p = skip_value(p,e);
                if(kl2==kl && strncmp(ks,key,kl)==0){ *vs=v; *vl=(size_t)(p-v); return 1; }
                p = skip_ws(p,e);
                if(p<e && *p==',') p++;
            } else {
                p = skip_value(p,e);
                p = skip_ws(p,e);
                if(p<e && *p==',') p++;
            }
        } else {
            p = skip_value(p,e);
            p = skip_ws(p,e);
            if(p<e && *p==',') p++;
        }
    }
    return 0;
}

/* 把带引号的 JSON 字符串 token 解码为 C 字符串（malloc，调用方 free） */
static char *json_unescape(const char *v, size_t vl){
    const char *e = v + vl;
    v = skip_ws(v,e);
    if(v>=e || *v!='"') return NULL;
    v++;
    SB s; sb_init(&s);
    while(v<e && *v!='"'){
        if(*v=='\\' && v+1<e){
            v++;
            switch(*v){
                case 'n': sb_putc(&s,'\n'); break;
                case 'r': sb_putc(&s,'\r'); break;
                case 't': sb_putc(&s,'\t'); break;
                case 'b': sb_putc(&s,'\b'); break;
                case 'f': sb_putc(&s,'\f'); break;
                case '/': sb_putc(&s,'/');  break;
                case '"': sb_putc(&s,'"');  break;
                case '\\': sb_putc(&s,'\\'); break;
                case 'u': {
                    if(v+4<e){
                        char h[5]; memcpy(h,v+1,4); h[4]=0;
                        unsigned cp = (unsigned)strtoul(h,NULL,16);
                        v += 4;
                        if(cp<0x80){ sb_putc(&s,(char)cp); }
                        else if(cp<0x800){
                            sb_putc(&s,(char)(0xC0|(cp>>6)));
                            sb_putc(&s,(char)(0x80|(cp&0x3F)));
                        } else {
                            sb_putc(&s,(char)(0xE0|(cp>>12)));
                            sb_putc(&s,(char)(0x80|((cp>>6)&0x3F)));
                            sb_putc(&s,(char)(0x80|(cp&0x3F)));
                        }
                    }
                    break;
                }
                default: sb_putc(&s,*v);
            }
            v++;
        } else { sb_putc(&s,*v); v++; }
    }
    return s.p;
}

/* 顶层 key → 解码后的 C 字符串 */
static int json_str(const char *obj, const char *e, const char *key, char *out, size_t n){
    const char *v; size_t vl;
    if(!json_find(obj,e,key,&v,&vl)) return 0;
    char *s = json_unescape(v,vl);
    if(!s) return 0;
    snprintf(out,n,"%s",s);
    free(s);
    return 1;
}

/* 顶层 key → 原始 token（不转义，用于回显 id / 原样转发） */
static int json_raw(const char *obj, const char *e, const char *key, char *out, size_t n){
    const char *v; size_t vl;
    if(!json_find(obj,e,key,&v,&vl)) return 0;
    if(vl>=n) vl = n-1;
    memcpy(out,v,vl); out[vl]=0;
    return 1;
}

static int json_int(const char *obj, const char *e, const char *key, int def){
    const char *v; size_t vl;
    if(!json_find(obj,e,key,&v,&vl)) return def;
    char b[32]; size_t n = vl<31?vl:31;
    memcpy(b,v,n); b[n]=0;
    return atoi(b);
}/* ============================================================
 * 工具注册表（与 Java 内核同源：JSON 薄壳 + legacy 目录）
 * ============================================================ */
typedef struct { char *name, *cat, *desc, *params, *cmd; int timeout; } Tool;
static Tool *TL = NULL; static int TN = 0, TCAP = 0;

static Tool *reg_find(const char *name){
    for(int i=0;i<TN;i++) if(strcmp(TL[i].name,name)==0) return &TL[i];
    return NULL;
}

static void reg_add(const char *name,const char *cat,const char *desc,
                    const char *params,const char *cmd,int timeout){
    if(!name || !*name || !cmd || !*cmd) return;
    if(reg_find(name)) return;                /* 先到先得，与 Java Registry.register 同语义 */
    if(TN==TCAP){
        TCAP = TCAP ? TCAP*2 : 256;
        TL = (Tool*)realloc(TL,(size_t)TCAP*sizeof(Tool));
        if(!TL) return;
    }
    Tool *t = &TL[TN++];
    t->name   = strdup(name);
    t->cat    = strdup(cat && *cat ? cat : "Legacy");
    t->desc   = strdup(desc ? desc : "");
    t->params = strdup(params ? params : "");
    t->cmd    = strdup(cmd);
    t->timeout= timeout>0 ? timeout : 60000;
}

static void reg_clear(void){
    for(int i=0;i<TN;i++){
        free(TL[i].name); free(TL[i].cat); free(TL[i].desc); free(TL[i].params); free(TL[i].cmd);
    }
    TN = 0;
}

static char *read_file(const char *path, size_t *len){
    int fd = open(path,O_RDONLY);
    if(fd<0) return NULL;
    struct stat st;
    if(fstat(fd,&st)<0 || st.st_size<=0 || st.st_size>(4u*1024*1024)){ close(fd); return NULL; }
    char *b = (char*)malloc((size_t)st.st_size+1);
    if(!b){ close(fd); return NULL; }
    ssize_t rd=0, n;
    while(rd<st.st_size && (n=read(fd,b+rd,st.st_size-rd))>0) rd+=n;
    close(fd);
    b[rd]=0;
    if(len) *len=(size_t)rd;
    return b;
}

/* 载入单个 JSON 薄壳工具；返回 1=成功 */
static int load_tool_json(const char *path){
    size_t l; char *j = read_file(path,&l);
    if(!j) return 0;
    const char *e = j+l;
    static char name[512], cat[256], desc[8192], params[16384], cmd[131072];
    int ok = 0;
    if(json_str(j,e,"name",name,sizeof name) && json_str(j,e,"cmd",cmd,sizeof cmd)){
        if(!json_str(j,e,"cat",cat,sizeof cat))    cat[0]=0;
        if(!json_str(j,e,"desc",desc,sizeof desc)) snprintf(desc,sizeof desc,"动态工具");
        if(!json_str(j,e,"params",params,sizeof params)) params[0]=0;
        reg_add(name,cat,desc,params,cmd,json_int(j,e,"timeoutMs",60000));
        ok = 1;
    }
    free(j);
    return ok;
}

/* 目录扫描载入（按文件名排序，保证加载顺序稳定可复现） */
static int load_dir(const char *dir){
    DIR *d = opendir(dir);
    if(!d) return 0;
    struct dirent *de;
    char **names = NULL; int nn = 0, cap = 0;
    while((de = readdir(d))){
        size_t l = strlen(de->d_name);
        if(l<6 || strcmp(de->d_name+l-5,".json")!=0) continue;
        if(nn==cap){ cap = cap? cap*2 : 64; names=(char**)realloc(names,(size_t)cap*sizeof(char*)); }
        names[nn++] = strdup(de->d_name);
    }
    closedir(d);
    for(int i=1;i<nn;i++){                       /* 插入排序 */
        char *k = names[i]; int j = i-1;
        while(j>=0 && strcmp(names[j],k)>0){ names[j+1]=names[j]; j--; }
        names[j+1] = k;
    }
    char path[1024];
    int n = 0;
    for(int i=0;i<nn;i++){
        snprintf(path,sizeof path,"%s/%s",dir,names[i]);
        n += load_tool_json(path);
        free(names[i]);
    }
    free(names);
    return n;
}

static void pilot_register(void);   /* LemonPilot 内建原生工具（定义见后） */
static void registry_load(void){
    int a = load_dir(PLUGIN_DIR);
    int b = load_dir(LEGACY_DIR);
    pilot_register();
    fprintf(stderr,"[lemoncore] registry: plugins=%d legacy=%d total=%d\n",a,b,TN);
}/* ============================================================
 * 参数收集 / 命令构造 / 执行器
 * ============================================================ */
typedef struct { char *k; char *v; } Arg;
#define MAXARGS 64
static Arg g_args[MAXARGS];
static int  g_nargs = 0;

static void args_reset(void){
    for(int i=0;i<g_nargs;i++){ free(g_args[i].k); free(g_args[i].v); }
    g_nargs = 0;
}

/* 收集 arguments 对象的第一层成员（值统一转 C 字符串） */
static void args_collect(const char *aobj, size_t alen){
    args_reset();
    const char *e = aobj + alen;
    const char *p = skip_ws(aobj,e);
    if(p<e && *p=='{') p++;
    while(p<e && g_nargs<MAXARGS){
        p = skip_ws(p,e);
        if(p>=e || *p=='}') break;
        if(*p!='"'){
            p = skip_value(p,e);
            p = skip_ws(p,e);
            if(p<e && *p==',') p++;
            continue;
        }
        const char *ks = ++p;
        while(p<e){ if(*p=='\\'){p+=2;continue;} if(*p=='"')break; p++; }
        size_t kl = (size_t)(p-ks);
        if(p<e) p++;
        p = skip_ws(p,e);
        if(p<e && *p==':'){
            p++;
            const char *vs = skip_ws(p,e);
            p = skip_value(p,e);
            Arg *a = &g_args[g_nargs++];
            a->k = (char*)malloc(kl+1);
            memcpy(a->k,ks,kl); a->k[kl]=0;
            if(vs<p && *vs=='"'){
                char *u = json_unescape(vs,(size_t)(p-vs));
                a->v = u ? u : strdup("");
            } else {
                size_t vl = (size_t)(p-vs);
                if(vl>65536) vl=65536;
                a->v = (char*)malloc(vl+1);
                memcpy(a->v,vs,vl); a->v[vl]=0;
            }
            p = skip_ws(p,e);
            if(p<e && *p==',') p++;
        } else {
            p = skip_value(p,e);
            p = skip_ws(p,e);
            if(p<e && *p==',') p++;
        }
    }
}

/* 模板 $param 替换（最长 key 优先 + 词边界检查，值按 Shell.q 单引号转义） */
static void subst_cmd(const char *tpl, SB *out){
    const char *p = tpl;
    while(*p){
        if(*p=='$'){
            int best = -1; size_t bestlen = 0;
            for(int i=0;i<g_nargs;i++){
                size_t kl = strlen(g_args[i].k);
                if(kl<=bestlen) continue;
                if(strncmp(p+1,g_args[i].k,kl)!=0) continue;
                char nx = p[1+kl];
                if(nx==0 || !(isalnum((unsigned char)nx)||nx=='_')){ best=i; bestlen=kl; }
            }
            if(best>=0){
                sb_putc(out,'\'');
                for(const char *q=g_args[best].v; *q; q++){
                    if(*q=='\'') sb_put(out,"'\\''");
                    else sb_putc(out,*q);
                }
                sb_putc(out,'\'');
                p += 1+bestlen;
            } else { sb_putc(out,*p); p++; }
        } else { sb_putc(out,*p); p++; }
    }
}

/* 执行 shell：合并 stdout+stderr，超时杀进程返回 code=124（与 Java Shell.exec 同语义） */
static void run_sh(const char *cmd, int timeout_ms, SB *out, int *exit_code){
    *exit_code = -1;
    int pfd[2];
    if(pipe(pfd)<0){ sb_put(out,"exec error: pipe failed"); return; }
    pid_t pid = fork();
    if(pid<0){ close(pfd[0]); close(pfd[1]); sb_put(out,"exec error: fork failed"); return; }
    if(pid==0){
        setpgid(0,0);
        close(pfd[0]);
        dup2(pfd[1],1); dup2(pfd[1],2);
        close(pfd[1]);
        execl("/system/bin/sh","sh","-c",cmd,(char*)NULL);
        _exit(127);
    }
    close(pfd[1]);
    setpgid(pid,pid);
    long deadline = now_ms() + timeout_ms;
    char buf[16384];
    int timeout = 0;
    size_t total = 0;
    for(;;){
        long rem = deadline - now_ms();
        if(rem<=0){ timeout=1; break; }
        struct pollfd pf; pf.fd=pfd[0]; pf.events=POLLIN; pf.revents=0;
        int pr = poll(&pf,1,(int)rem);
        if(pr<0){ if(errno==EINTR) continue; timeout=1; break; }
        if(pr==0){ timeout=1; break; }
        ssize_t n = read(pfd[0],buf,sizeof buf);
        if(n<0){ if(errno==EINTR) continue; break; }
        if(n==0) break;
        if(total < MAX_OUT){
            size_t take = (size_t)n;
            if(total+take > MAX_OUT) take = MAX_OUT-total;
            sb_putn(out,buf,take);
            total += take;
        }
    }
    close(pfd[0]);
    if(timeout){ kill(-pid,SIGKILL); kill(pid,SIGKILL); }   /* 整进程组，防孤儿 */
    int st = 0, reaped = 0;
    for(int i=0;i<100;i++){
        pid_t r = waitpid(pid,&st,WNOHANG);
        if(r==pid){ reaped = 1; break; }
        if(r<0 && errno==ECHILD){ reaped = 1; st = 0; break; }
        usleep(10000);
    }
    *exit_code = timeout ? 124 : (reaped && WIFEXITED(st) ? WEXITSTATUS(st) : 125);
}/* ============================================================
 * 上游桥：本地没有的工具（Java 编译内建类）转发 8788 Java 内核
 * ============================================================ */
static int write_all(int fd, const char *b, size_t n){
    size_t off = 0;
    while(off < n){
        ssize_t w = write(fd, b+off, n-off);
        if(w<0){ if(errno==EINTR) continue; return -1; }
        off += (size_t)w;
    }
    return 0;
}

static int ci_contains(const char *s, size_t n, const char *needle){
    size_t nl = strlen(needle);
    if(nl==0 || nl>n) return 0;
    for(size_t i=0;i+nl<=n;i++){
        size_t j=0;
        while(j<nl && tolower((unsigned char)s[i+j])==tolower((unsigned char)needle[j])) j++;
        if(j==nl) return 1;
    }
    return 0;
}

/* 极简 HTTP POST，读响应（读至对端关闭/超时），分离出响应体 */
static int http_post(const char *host,int port,const char *path,
                     const char *body,size_t blen,long timeout_ms,SB *resp){
    int fd = socket(AF_INET,SOCK_STREAM,0);
    if(fd<0) return -1;
    struct timeval tv; tv.tv_sec=(int)(timeout_ms/1000); tv.tv_usec=(suseconds_t)((timeout_ms%1000)*1000);
    setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof tv);
    setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof tv);
    struct sockaddr_in sa; memset(&sa,0,sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_port   = htons((uint16_t)port);
    if(inet_pton(AF_INET,host,&sa.sin_addr)!=1){ close(fd); return -1; }
    if(connect(fd,(struct sockaddr*)&sa,sizeof sa)<0){ close(fd); return -1; }
    char head[512];
    snprintf(head,sizeof head,
        "POST %s HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: application/json\r\n"
        "Content-Length: %zu\r\nConnection: close\r\n\r\n",
        path,host,port,blen);
    if(write_all(fd,head,strlen(head))<0 || write_all(fd,body,blen)<0){ close(fd); return -1; }
    SB raw; sb_init(&raw);
    char rbuf[16384];
    long deadline = now_ms()+timeout_ms;
    for(;;){
        long rem = deadline-now_ms();
        if(rem<=0) break;
        struct pollfd pf; pf.fd=fd; pf.events=POLLIN; pf.revents=0;
        int pr = poll(&pf,1,(int)rem);
        if(pr<=0) break;
        ssize_t n = read(fd,rbuf,sizeof rbuf);
        if(n<0){ if(errno==EINTR) continue; break; }
        if(n==0) break;
        sb_putn(&raw,rbuf,(size_t)n);
        if(raw.len > (32u*1024*1024)) break;
    }
    close(fd);
    const char *h = raw.p;
    const char *sep = strstr(h,"\r\n\r\n");
    size_t hlen, roff;
    if(sep){ hlen=(size_t)(sep-h); roff=(size_t)(sep-h+4); }
    else { hlen=raw.len; roff=raw.len; }
    /* 解析 Content-Length / chunked，剥出响应体 */
    int has_cl=0, chunked=0; size_t cl=0;
    {
        const char *p=h, *e=h+hlen;
        while(p<e){
            const char *le = (const char*)memchr(p,'\n',(size_t)(e-p));
            if(!le) le=e;
            size_t ll=(size_t)(le-p);
            if(ll && p[ll-1]=='\r') ll--;
            if(ll>15 && strncasecmp(p,"Content-Length:",15)==0){
                char b[32]; size_t t = ll-15<31?ll-15:31;
                memcpy(b,p+15,t); b[t]=0; cl=strtoul(b,NULL,10); has_cl=1;
            }
            if(ll>17 && strncasecmp(p,"Transfer-Encoding:",18)==0 && ci_contains(p+18,ll-18,"chunked")) chunked=1;
            p = le+1;
        }
    }
    const char *rest = h+roff; size_t rest_len = raw.len-roff;
    if(chunked){
        const char *q=rest, *qe=rest+rest_len;
        for(;;){
            const char *le=(const char*)memchr(q,'\n',(size_t)(qe-q));
            if(!le) break;
            char szb[24]; size_t sl=(size_t)(le-q);
            if(sl && q[sl-1]=='\r') sl--;
            if(sl>=sizeof szb) sl=sizeof szb-1;
            memcpy(szb,q,sl); szb[sl]=0;
            unsigned long csz = strtoul(szb,NULL,16);
            q = le+1;
            if(csz==0) break;
            if((size_t)(qe-q) < csz) csz = (unsigned long)(qe-q);
            sb_putn(resp,q,csz);
            q += csz;
            if(q<qe && *q=='\r') q++;
            if(q<qe && *q=='\n') q++;
        }
    } else if(has_cl){
        size_t take = rest_len<cl ? rest_len : cl;
        sb_putn(resp,rest,take);
    } else {
        sb_putn(resp,rest,rest_len);
    }
    sb_free(&raw);
    return 0;
}

static int up_post_json(const char *jreq, SB *resp, long timeout_ms){
    return http_post(UP_HOST,UP_PORT,"/mcp",jreq,strlen(jreq),timeout_ms,resp);
}

/* 转发 tools/call，抽出 result.content[].text 拼接 */
static int up_call(const char *tool, const char *args_raw, size_t args_len, SB *text, long timeout_ms){
    M->upstream++;
    SB req; sb_init(&req);
    sb_put(&req,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":");
    sb_json_str(&req,tool);
    sb_put(&req,",\"arguments\":");
    if(args_len && args_raw) sb_putn(&req,args_raw,args_len);
    else sb_put(&req,"{}");
    sb_put(&req,"}}");
    SB resp; sb_init(&resp);
    int rc = up_post_json(req.p,&resp,timeout_ms);
    sb_free(&req);
    if(rc<0){ sb_free(&resp); return -1; }
    const char *rj = resp.p, *re = resp.p+resp.len;
    int found = 0, remote_err = 0;
    const char *rv=NULL; size_t rl=0;
    if(json_find(rj,re,"result",&rv,&rl)){
        {
            const char *iv=NULL; size_t il=0;
            if(json_find(rv,rv+rl,"isError",&iv,&il) && il>=4 && strncmp(iv,"true",4)==0) remote_err = 1;
        }
        const char *cv=NULL; size_t cl=0;
        if(json_find(rv,rv+rl,"content",&cv,&cl) && cl>1 && cv[0]=='['){
            const char *p = skip_ws(cv,cv+cl);
            if(p<cv+cl && *p=='[') p++;
            for(;;){
                p = skip_ws(p,cv+cl);
                if(p>=cv+cl || *p==']') break;
                const char *ms = p;
                p = skip_value(p,cv+cl);
                size_t ml = (size_t)(p-ms);
                const char *tv=NULL; size_t tl=0;
                char *ty = NULL;
                if(json_find(ms,ms+ml,"type",&tv,&tl)) ty = json_unescape(tv,tl);
                if(!ty || strcmp(ty,"text")==0){
                    if(json_find(ms,ms+ml,"text",&tv,&tl)){
                        char *tx = json_unescape(tv,tl);
                        if(tx){ sb_put(text,tx); free(tx); found=1; }
                    }
                }
                if(ty) free(ty);
                p = skip_ws(p,cv+cl);
                if(p<cv+cl && *p==',') p++;
            }
        } else if(rv && rl){
            sb_putn(text,rv,rl>65536?65536:rl); found=1;
        }
        if(!found && rl){
            char *raw = (char*)malloc(rl+1);
            if(raw){ memcpy(raw,rv,rl); raw[rl]=0; sb_put(text,raw); free(raw); found=1; }
        }
    } else {
        const char *ev=NULL; size_t el=0;
        remote_err = 1;
        if(json_find(rj,re,"error",&ev,&el)){
            const char *mv=NULL; size_t ml=0;
            if(json_find(ev,ev+el,"message",&mv,&ml)){
                char *mm = json_unescape(mv,ml);
                if(mm){ sb_put(text,mm); free(mm); found=1; }
            }
        }
        if(!found){
            sb_put(text,"upstream error: ");
            sb_putn(text,rj,resp.len>512?512:resp.len);
            found=1;
        }
    }
    sb_free(&resp);
    return found ? (remote_err?2:0) : -1;
}

/* 上游 tools/list 原始数组（60s 缓存） */
static SB up_tools_cache; static int up_tools_inited = 0;
static int up_tools_ok = 0; static long up_tools_ts = 0;

static const char *up_tools_cached(void){
    long now = now_ms();
    if(up_tools_ok && now-up_tools_ts < 60000) return up_tools_cache.p;
    SB req; sb_init(&req);
    sb_put(&req,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    SB resp; sb_init(&resp);
    if(up_post_json(req.p,&resp,10000)<0){ sb_free(&req); sb_free(&resp); return up_tools_ok?up_tools_cache.p:NULL; }
    sb_free(&req);
    const char *rj=resp.p, *re=resp.p+resp.len;
    const char *rv=NULL, *tv=NULL; size_t rl=0, tl=0;
    if(json_find(rj,re,"result",&rv,&rl) && json_find(rv,rv+rl,"tools",&tv,&tl) && tl>1 && tv[0]=='['){
        if(!up_tools_inited){ sb_init(&up_tools_cache); up_tools_inited=1; }
        else { up_tools_cache.len=0; up_tools_cache.p[0]=0; }
        sb_putn(&up_tools_cache,tv,tl);
        up_tools_ok = 1; up_tools_ts = now;
    }
    sb_free(&resp);
    return up_tools_ok ? up_tools_cache.p : NULL;
}/* ============================================================
 * MCP 方法层（响应格式与 Java 内核完全同构）
 * ============================================================ */
/* 把 "a:string:desc,b:string:desc" 里真正的参数分隔符统一成 '|'
 * 判据：分隔符后（跳过空格）必须形如 key: 才算分隔，
 * 否则是描述里的普通逗号（如 "path:string:相对路径,可空列根目录"）。*/
static void normalize_params(char *s){
    char *p=s;
    while(*p){
        if(*p==','||*p=='|'){
            char *q=p+1; while(*q==' ') q++;
            char *r=q;
            while((*r>='A'&&*r<='Z')||(*r>='a'&&*r<='z')||(*r>='0'&&*r<='9')||*r=='_'||*r=='-') r++;
            if(r>q && *r==':') *p='|';
        }
        p++;
    }
}

static void sb_params_schema(SB *s, const char *params){
    sb_put(s,"{\"type\":\"object\",\"properties\":{");
    int first=1;
    if(params && *params){
        char *tmp = strdup(params);
        normalize_params(tmp);
        char *sp = NULL;
        char *seg = strtok_r(tmp,"|",&sp);
        while(seg){
            char *c1 = strchr(seg,':');
            if(c1){
                *c1=0;
                char *k=seg, *rest=c1+1;
                char *c2 = strchr(rest,':');
                const char *ty="string", *ds="";
                if(c2){ *c2=0; ty=rest; ds=c2+1; }
                else { ty=rest; }
                if(!first) sb_putc(s,',');
                first=0;
                sb_json_str(s,k);
                sb_put(s,":{\"type\":"); sb_json_str(s,ty);
                sb_put(s,",\"description\":"); sb_json_str(s,ds);
                sb_put(s,"}");
            }
            seg = strtok_r(NULL,"|",&sp);
        }
        free(tmp);
    }
    sb_put(s,"}}");
}

/* ---------- 精简模式（lean）：只暴露白名单工具，避免 980 工具灌爆客户端上下文 ---------- */
static int  LEAN_STATE = -1;
static char *LEAN_BUF = NULL;

static void lean_load(void){
    if(LEAN_STATE>=0) return;
    LEAN_STATE = 0;
    const char *f = getenv("LEMON_LEAN_FILE");
    if(!f) f = "/data/adb/lemon_core/lean.txt";
    FILE *fp = fopen(f,"r");
    if(fp){
        fseek(fp,0,SEEK_END); long n=ftell(fp); fseek(fp,0,SEEK_SET);
        if(n>0 && n<65536){
            LEAN_BUF = malloc((size_t)n+1);
            if(LEAN_BUF){
                size_t r=fread(LEAN_BUF,1,(size_t)n,fp);
                LEAN_BUF[r]=0;
            }
        }
        fclose(fp);
    }
    const char *e = getenv("LEMON_LEAN");
    if(e) LEAN_STATE = atoi(e) ? 1 : 0;
    else  LEAN_STATE = (LEAN_BUF && LEAN_BUF[0]) ? 1 : 0;
}

static int lean_on(void){ lean_load(); return LEAN_STATE; }

static int lean_match(const char *name){
    if(!LEAN_BUF || !LEAN_BUF[0]) return 0;
    const char *p = LEAN_BUF;
    while(*p){
        const char *nl = strchr(p,'\n');
        size_t len = nl ? (size_t)(nl-p) : strlen(p);
        if(len && p[0]!='#'){
            if(p[len-1]==':'){
                if(strncmp(name,p,len-1)==0) return 1;
            } else if(strlen(name)==len && strncmp(name,p,len)==0) return 1;
        }
        if(!nl) break;
        p = nl+1;
    }
    return 0;
}

static void sb_tools_list(SB *out, int all){
    int lean = (!all) && lean_on();
    sb_put(out,"{\"tools\":[");
    int first=1;
    for(int i=0;i<TN;i++){
        if(lean && !lean_match(TL[i].name)) continue;
        if(!first) sb_putc(out,',');
        first=0;
        sb_put(out,"{\"name\":"); sb_json_str(out,TL[i].name);
        char dbuf[9216];
        if(TL[i].desc[0]=='[') snprintf(dbuf,sizeof dbuf,"%s",TL[i].desc);
        else snprintf(dbuf,sizeof dbuf,"[%s] %s",TL[i].cat,TL[i].desc);
        sb_put(out,",\"description\":"); sb_json_str(out,dbuf);
        sb_put(out,",\"inputSchema\":"); sb_params_schema(out,TL[i].params);
        sb_put(out,"}");
    }
    int remote = 0;
    if(UP_ENABLED){
        const char *arr = up_tools_cached();
        if(arr){
            size_t al = strlen(arr);
            const char *p = skip_ws(arr,arr+al);
            if(p<arr+al && *p=='[') p++;
            for(;;){
                p = skip_ws(p,arr+al);
                if(p>=arr+al || *p==']') break;
                const char *ms = p;
                p = skip_value(p,arr+al);
                size_t ml = (size_t)(p-ms);
                char nm[512];
                if(json_str(ms,ms+ml,"name",nm,sizeof nm) && !reg_find(nm) && !(lean && !lean_match(nm))){
                    if(!first) sb_putc(out,',');
                    first=0;
                    sb_putn(out,ms,ml);
                    remote++;
                }
                p = skip_ws(p,arr+al);
                if(p<arr+al && *p==',') p++;
            }
        }
    }
    sb_put(out,"],\"local\":"); sb_puti(out,TN);
    sb_put(out,",\"remote_added\":"); sb_puti(out,remote);
    sb_put(out,",\"total\":"); sb_puti(out,TN+remote);
    sb_put(out,",\"upstream_enabled\":"); sb_put(out,UP_ENABLED?"true":"false");
    sb_put(out,"}");
}

/* ---------- #418 防上下文撑爆：统一分流（限长 + 落盘） ----------
 * 四条返回路径（本地成功/本地失败/上游成功/上游失败）全走 sb_result_text，
 * 在此一处收口：超限 → UTF-8 安全截断 + 全文落盘 toolout/ + 一行提示。
 * g_slim_max：-1=未指定(默认4096)  0=全量(内部检测器专用)  >0=覆盖上限
 * fork-per-connection 模型：全局变量每连接独立进程，天然隔离。 ---------- */
static const char *TOOLOUT_DIR = "/data/lemonbrain/logs/toolout";
#define SLIM_DEFAULT_LIMIT 4096
static char g_slim_tool[160] = "";
static long g_slim_max = -1;

static size_t utf8_clip(const char *s, size_t n){
    if(n==0 || !s) return 0;
    /* ① 截断点落在续字节上(10xxxxxx)：回退到字符首 */
    while(n>0 && (s[n-1]&0xC0)==0x80) n--;
    if(n==0) return 0;
    /* ② 截断点落在多字节字符首字节上(C2~F4)但该字符不完整：整字符丢弃 */
    unsigned char c = (unsigned char)s[n-1];
    if(c>=0xC2 && c<=0xF4) n--;
    return n;
}

static int slim_toolout_dump(const char *text, size_t len, char *path_out, size_t psz){
    struct stat st;
    /* mkdir 竞态(EEXIST)：另一并发连接先建了 = 目录可用，不算失败 */
    if(stat("/data/lemonbrain/logs",&st)!=0 &&
       (mkdir("/data/lemonbrain/logs",0755)!=0 && errno!=EEXIST)) return 0;
    if(stat(TOOLOUT_DIR,&st)!=0 &&
       (mkdir(TOOLOUT_DIR,0755)!=0 && errno!=EEXIST)) return 0;
    char ts[32];
    time_t tsec = time(NULL);
    struct tm tmv; localtime_r(&tsec,&tmv);
    snprintf(ts,sizeof ts,"%04d%02d%02d-%02d%02d%02d",
        tmv.tm_year+1900,tmv.tm_mon+1,tmv.tm_mday,tmv.tm_hour,tmv.tm_min,tmv.tm_sec);
    char safe[128]; size_t si=0;
    for(size_t i=0; g_slim_tool[i] && si<sizeof safe-1; i++){
        char c=g_slim_tool[i];
        safe[si++]=(isalnum((unsigned char)c)||c=='_'||c=='-')?c:'_';
    }
    safe[si]=0;
    if(!safe[0]){ safe[0]='t'; safe[1]=0; }
    snprintf(path_out,psz,"%s/%s-p%d-%s.txt",TOOLOUT_DIR,ts,(int)getpid(),safe);
    int fd=open(path_out,O_WRONLY|O_CREAT|O_TRUNC,0644);
    if(fd<0) return 0;
    size_t off=0;
    while(off<len){
        ssize_t w=write(fd,text+off,len-off);
        if(w<=0){ close(fd); return 0; }
        off+=(size_t)w;
    }
    close(fd);
    return 1;
}

/* 在原始 JSON 片段里找 "key":数字（仅用于 maxChars，宽松匹配可接受：
 * 误命中只影响限长数值，全文永远落盘兜底，不丢数据） */
static long json_find_num(const char *s, size_t n, const char *key){
    /* 状态机解析：in_str 内的一切内容都只是字符串值，不参与匹配。
     * 这是为了挡住「参数值里内嵌 "maxChars":5 字样被误当配置」的针孔。 */
    char pat[64]; snprintf(pat,sizeof pat,"\"%s\"",key);
    size_t pl=strlen(pat);
    int in_str = 0;
    for(size_t i=0;i+pl<=n;i++){
        char c = s[i];
        if(in_str){
            if(c=='\\'){ i++; if(i+pl>n) break; }
            else if(c=='\"') in_str = 0;
            continue;
        }
        if(c=='\"'){
            if(memcmp(s+i,pat,pl)==0 && i+pl<n){
                size_t j=i+pl;
                while(j<n && (s[j]==' '||s[j]==':'||s[j]=='\t')) j++;
                if(j<n && (isdigit((unsigned char)s[j])||s[j]=='-'))
                    return strtol(s+j,NULL,10);
            }
            in_str = 1;   /* 非目标 key：进入字符串，跳过后续字符直到下一个未转义引号 */
            continue;
        }
    }
    return -1;
}

static void sb_result_text(SB *out, const char *idraw, const char *text, int is_error){
    long slim_lim = (g_slim_max >= 0) ? g_slim_max : SLIM_DEFAULT_LIMIT;
    size_t slim_len = text ? strlen(text) : 0;
    if(slim_lim > 0 && (long)slim_len > slim_lim){
        char pbuf[512];
        int dumped = slim_toolout_dump(text, slim_len, pbuf, sizeof pbuf);
        size_t cut = utf8_clip(text, (size_t)slim_lim);
        /* 截断体+提示拼成完整文本后统一走 sb_json_str 转义（防换行/引号打坏JSON） */
        size_t need = cut + 700;
        char *buf = (char*)malloc(need);
        if(buf){
            size_t off = 0;
            if(cut){ memcpy(buf, text, cut); off = cut; }
            int w = snprintf(buf+off, need-off,
                "\n[slim] 输出 %dB 超 %dB 上限", (int)slim_len, (int)slim_lim);
            if(w>0 && (size_t)w < need-off) off += (size_t)w;
            if(dumped) w = snprintf(buf+off, need-off, "，全文：%s", pbuf);
            else       w = snprintf(buf+off, need-off, "（落盘失败，请查 toolout 目录权限）");
            if(w>0 && (size_t)w < need-off) off += (size_t)w;
            buf[off] = 0;
            sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":");
            sb_put(out, idraw && *idraw ? idraw : "null");
            sb_put(out,",\"result\":{\"content\":[{\"type\":\"text\",\"text\":");
            sb_json_str(out, buf);
            sb_put(out,"}],\"isError\":");
            sb_put(out, is_error?"true":"false");
            sb_put(out,"}}");
            free(buf);
            return;
        }
        /* malloc 失败：不截断走原路径，宁可不省也不坏数据 */
    }
    sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":");
    sb_put(out, idraw && *idraw ? idraw : "null");
    sb_put(out,",\"result\":{\"content\":[{\"type\":\"text\",\"text\":");
    sb_json_str(out, text?text:"");
    sb_put(out,"}],\"isError\":");
    sb_put(out, is_error?"true":"false");
    sb_put(out,"}}");
}

static void sb_error(SB *out, const char *idraw, int code, const char *msg){
    sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":");
    sb_put(out, idraw && *idraw ? idraw : "null");
    sb_put(out,",\"error\":{\"code\":"); sb_puti(out,code);
    sb_put(out,",\"message\":"); sb_json_str(out,msg);
    sb_put(out,"}}");
}

/* 统计上游工具里本地没有的数量 */
static int up_unique_count(void){
    if(!UP_ENABLED) return 0;
    const char *arr = up_tools_cached();
    if(!arr) return 0;
    int n=0;
    size_t al = strlen(arr);
    const char *p = skip_ws(arr,arr+al);
    if(p<arr+al && *p=='[') p++;
    for(;;){
        p = skip_ws(p,arr+al);
        if(p>=arr+al || *p==']') break;
        const char *ms = p;
        p = skip_value(p,arr+al);
        char nm[512];
        if(json_str(ms,ms+(size_t)(p-ms),"name",nm,sizeof nm) && !reg_find(nm)) n++;
        p = skip_ws(p,arr+al);
        if(p<arr+al && *p==',') p++;
    }
    return n;
}

/* ============================================================
 * LemonPilot · 内核级数据感知智能体 —— 内建原生工具 (v1.3.0)
 * 授权：lemon-user · 2026-11-16
 * 感知：控件树语义解析（数据优先，视觉兜底）
 * 执行：动作原语 + 三级降级基础（协议/语义/GUI）
 * 结晶：成功路径沉淀为技能脚本，回放零Token
 * 护栏：敏感动作强制 confirm=1 + JSONL 全程审计
 * ============================================================ */
#define PILOT_DIR    "/data/lemonbrain/pilot"
#define PILOT_SKILLS PILOT_DIR "/skills"
#define PILOT_AUDIT  PILOT_DIR "/audit"
#define PILOT_UI     PILOT_DIR "/ui.xml"

typedef struct { char text[256], id[192], cls[96], pkg[96]; int x1,y1,x2,y2,click; } PElem;

static const char *parg(const char *k){
    for(int i=0;i<g_nargs;i++) if(strcmp(g_args[i].k,k)==0) return g_args[i].v;
    return NULL;
}
static int parg_int(const char *k, int def){ const char *v=parg(k); return (v&&*v)?atoi(v):def; }

static void pilot_dirs(void){
    mkdir(PILOT_DIR,0755); mkdir(PILOT_SKILLS,0755); mkdir(PILOT_AUDIT,0755);
}

/* ---- v1.4.0 动态数据绑定：任务内变量表 + {{var}} 替换 + POSIX正则提取 ---- */
typedef struct { char name[32]; char val[512]; } PVar;
static PVar g_pvars[16]; static int g_npvars = 0;
static void pvars_reset(void){ g_npvars = 0; }
static void pvar_set(const char *name, const char *val){
    if(!name||!*name) return;
    for(int i=0;i<g_npvars;i++) if(strcmp(g_pvars[i].name,name)==0){
        snprintf(g_pvars[i].val,sizeof g_pvars[i].val,"%s",val?val:""); return;
    }
    if(g_npvars>=16) return;
    snprintf(g_pvars[g_npvars].name,sizeof g_pvars[0].name,"%s",name);
    snprintf(g_pvars[g_npvars].val,sizeof g_pvars[0].val,"%s",val?val:"");
    g_npvars++;
}
static const char *pvar_get(const char *name){
    for(int i=0;i<g_npvars;i++) if(strcmp(g_pvars[i].name,name)==0) return g_pvars[i].val;
    return NULL;
}
static void pvar_subst(const char *in, char *out, size_t n){
    size_t o=0; const char *p=in;
    while(*p && o+1<n){
        if(p[0]=='{'&&p[1]=='{'){
            const char *e=strstr(p+2,"}}");
            if(e && (size_t)(e-(p+2))<32){
                char nm[32]; size_t l=(size_t)(e-(p+2));
                memcpy(nm,p+2,l); nm[l]=0;
                const char *v=pvar_get(nm);
                if(v){
                    size_t vl=strlen(v);
                    if(o+vl>=n) vl=n-o-1;
                    memcpy(out+o,v,vl); o+=vl; p=e+2; continue;
                }
            }
        }
        out[o++]=*p++;
    }
    out[o]=0;
}
/* 带变量替换的步骤字段读取 */
static int pj_str(const char *o, const char *e, const char *key, char *out, size_t n){
    if(!json_str(o,e,key,out,n)) return 0;
    char tmp[8192];
    pvar_subst(out,tmp,sizeof tmp);
    snprintf(out,n,"%s",tmp);
    return 1;
}
/* POSIX正则提取：有第1捕获组取组1，否则取整体匹配；返回malloc串 */
static char *pregex_extract(const char *pattern, const char *text){
    regex_t re; regmatch_t m[2];
    if(!pattern||!*pattern||!text) return NULL;
    if(regcomp(&re,pattern,REG_EXTENDED)!=0) return NULL;
    char *res=NULL;
    if(regexec(&re,text,2,m,0)==0){
        int g=(m[1].rm_so>=0)?1:0;
        size_t l=(size_t)(m[g].rm_eo-m[g].rm_so);
        res=(char*)malloc(l+1);
        if(res){ memcpy(res,text+m[g].rm_so,l); res[l]=0; }
    }
    regfree(&re);
    return res;
}

/* XML 属性值提取 + 实体反转义（仅在属性起始位置匹配，避免误中值内容） */
static int xml_attr(const char *s, const char *e, const char *key, char *out, size_t n){
    size_t kl=strlen(key);
    out[0]=0;
    for(const char *p=s;p+kl+2<e;p++){
        if((p==s||isspace((unsigned char)p[-1])) && strncmp(p,key,kl)==0 && p[kl]=='=' && p[kl+1]=='"'){
            const char *v=p+kl+2; const char *q=v;
            while(q<e && *q!='"') q++;
            size_t o=0;
            for(const char *c=v;c<q && o+1<n;c++){
                if(*c=='&'){
                    if(!strncmp(c,"&amp;",5)){out[o++]='&';c+=4;}
                    else if(!strncmp(c,"&lt;",4)){out[o++]='<';c+=3;}
                    else if(!strncmp(c,"&gt;",4)){out[o++]='>';c+=3;}
                    else if(!strncmp(c,"&quot;",6)){out[o++]='"';c+=5;}
                    else if(!strncmp(c,"&apos;",6)){out[o++]='\'';c+=5;}
                    else out[o++]=*c;
                } else out[o++]=*c;
            }
            out[o]=0; return 1;
        }
    }
    return 0;
}

/* 控件树 dump（失败自动重试一次） */
static int pilot_dump(char *err, size_t errn){
    SB o; sb_init(&o); int code=0;
    run_sh("uiautomator dump " PILOT_UI " >/dev/null 2>&1", 15000, &o, &code);
    if(code!=0){
        sb_free(&o); sb_init(&o);
        usleep(800000);
        run_sh("uiautomator dump " PILOT_UI " >/dev/null 2>&1", 15000, &o, &code);
    }
    sb_free(&o);
    if(code!=0){ snprintf(err,errn,"uiautomator dump 失败(code=%d)：可能锁屏/动画中/无障碍冲突",code); return 0; }
    return 1;
}

/* 解析 node 节点 → 语义元素表（只保留有 text 或 id 的语义节点） */
static int pilot_parse(const char *xml, PElem *arr, int max){
    int n=0; const char *p=xml;
    while(n<max && (p=strstr(p,"<node"))!=NULL){
        const char *q=p; int inq=0;
        while(*q){ if(*q=='"') inq=!inq; else if(*q=='>'&&!inq) break; q++; }
        if(!*q) break;
        PElem *el=&arr[n];
        memset(el,0,sizeof *el);
        xml_attr(p,q,"text",el->text,sizeof el->text);
        xml_attr(p,q,"resource-id",el->id,sizeof el->id);
        xml_attr(p,q,"class",el->cls,sizeof el->cls);
        xml_attr(p,q,"package",el->pkg,sizeof el->pkg);
        char b[64]="";
        if(xml_attr(p,q,"bounds",b,sizeof b)) sscanf(b,"[%d,%d][%d,%d]",&el->x1,&el->y1,&el->x2,&el->y2);
        char c[8]="";
        xml_attr(p,q,"clickable",c,sizeof c);
        el->click = (strcmp(c,"true")==0);
        if(el->text[0]||el->id[0]) n++;
        p=q+1;
    }
    return n;
}

static int pilot_load_ui(PElem **arr, char *err, size_t errn){
    if(!pilot_dump(err,errn)) return -1;
    size_t l=0; char *xml=read_file(PILOT_UI,&l);
    if(!xml){ snprintf(err,errn,"读取控件树文件失败"); return -1; }
    PElem *a=(PElem*)malloc(sizeof(PElem)*768);
    if(!a){ free(xml); snprintf(err,errn,"OOM"); return -1; }
    int n=pilot_parse(xml,a,768);
    free(xml);
    *arr=a; return n;
}

static int pilot_match(PElem *el, const char *text, const char *id){
    if(text&&*text && !strstr(el->text,text)) return 0;
    if(id&&*id && !strstr(el->id,id)) return 0;
    return 1;
}

/* 审计：JSONL 追加（按日分文件） */
static void pilot_audit(const char *task, int step, const char *act, const char *detail, const char *result, long ms){
    char path[256]; time_t t=time(NULL); struct tm tm; localtime_r(&t,&tm);
    snprintf(path,sizeof path,PILOT_AUDIT "/pilot-%04d%02d%02d.jsonl",tm.tm_year+1900,tm.tm_mon+1,tm.tm_mday);
    FILE *f=fopen(path,"a"); if(!f) return;
    SB s; sb_init(&s);
    sb_put(&s,"{\"ts\":"); sb_puti(&s,(long)t);
    sb_put(&s,",\"task\":"); sb_json_str(&s,task?task:"");
    sb_put(&s,",\"step\":"); sb_puti(&s,step);
    sb_put(&s,",\"act\":"); sb_json_str(&s,act?act:"");
    sb_put(&s,",\"detail\":"); sb_json_str(&s,detail?detail:"");
    sb_put(&s,",\"result\":"); sb_json_str(&s,result?result:"");
    sb_put(&s,",\"ms\":"); sb_puti(&s,ms);
    sb_put(&s,"}\n");
    fwrite(s.p,1,s.len,f); fclose(f); sb_free(&s);
}

static int pilot_name_ok(const char *s){
    if(!s||!*s||s[0]=='.') return 0;
    for(const unsigned char *p=(const unsigned char*)s;*p;p++)
        if(*p=='/'||*p=='\\'||*p=='"'||*p=='\''||*p<0x20) return 0;
    return 1;
}

static int pilot_write_skill(const char *name, const char *steps_raw, int runs){
    char path[512]; snprintf(path,sizeof path,PILOT_SKILLS "/%s.json",name);
    FILE *f=fopen(path,"w"); if(!f) return 0;
    SB s; sb_init(&s);
    sb_put(&s,"{\"name\":"); sb_json_str(&s,name);
    sb_put(&s,",\"updated\":"); sb_puti(&s,(long)time(NULL));
    sb_put(&s,",\"runs\":"); sb_puti(&s,runs);
    sb_put(&s,",\"steps\":"); sb_put(&s,steps_raw);
    sb_put(&s,"}\n");
    fwrite(s.p,1,s.len,f); fclose(f); sb_free(&s);
    return 1;
}

/* input text 转义：空格→%s，单引号→shell安全拼接 */
static void pilot_input_text(const char *txt, SB *cmd){
    sb_put(cmd,"input text '");
    for(const char *p=txt;*p;p++){
        if(*p==' ') sb_put(cmd,"%s");
        else if(*p=='\'') sb_put(cmd,"'\\''");
        else sb_putc(cmd,*p);
    }
    sb_putc(cmd,'\'');
}

static int pilot_pkg_ok(const char *s){
    if(!s||!*s) return 0;
    for(const char *p=s;*p;p++)
        if(!(isalnum((unsigned char)*p)||*p=='.'||*p=='_')) return 0;
    return 1;
}

/* ---- 工具1：pilot_perceive 语义感知 ---- */
static void pilot_perceive(SB *text){
    char err[256]=""; PElem *arr=NULL;
    int n=pilot_load_ui(&arr,err,sizeof err);
    if(n<0){ sb_put(text,err); return; }
    const char *kw=parg("keyword"); int limit=parg_int("limit",40);
    if(limit<1) limit=1; if(limit>200) limit=200;
    int shown=0, matched=0;
    sb_put(text,"[pilot] 语义元素表");
    if(n>0) { sb_put(text," · 当前包: "); sb_put(text,arr[0].pkg); }
    sb_putc(text,'\n');
    for(int i=0;i<n;i++){
        if(kw&&*kw && !pilot_match(&arr[i],kw,kw)) continue;
        matched++;
        if(shown>=limit) continue;
        sb_putc(text,'['); sb_puti(text,i); sb_put(text,"] ");
        if(arr[i].text[0]){ sb_put(text,"text="); sb_json_str(text,arr[i].text); sb_putc(text,' '); }
        if(arr[i].id[0]){ sb_put(text,"id="); sb_put(text,arr[i].id); sb_putc(text,' '); }
        sb_put(text,arr[i].cls);
        char b[80]; snprintf(b,sizeof b," [%d,%d][%d,%d]",arr[i].x1,arr[i].y1,arr[i].x2,arr[i].y2);
        sb_put(text,b);
        if(arr[i].click) sb_put(text," click");
        sb_putc(text,'\n');
        shown++;
    }
    sb_put(text,"共 "); sb_puti(text,n); sb_put(text," 个语义节点");
    if(kw&&*kw){ sb_put(text,"，关键词命中 "); sb_puti(text,matched); }
    if(matched>shown){ sb_put(text,"，仅显示前 "); sb_puti(text,shown); sb_put(text," 条(limit可调)"); }
    free(arr);
}

/* ---- 工具2：pilot_tap 语义点击 ---- */
static void pilot_tap(SB *text){
    const char *t=parg("text"), *id=parg("id");
    if((!t||!*t)&&(!id||!*id)){ sb_put(text,"需要 text 或 id 定位参数"); return; }
    char err[256]=""; PElem *arr=NULL;
    int n=pilot_load_ui(&arr,err,sizeof err);
    if(n<0){ sb_put(text,err); return; }
    int want=parg_int("index",0), hit=-1, cnt=0;
    for(int i=0;i<n;i++){
        if(!pilot_match(&arr[i],t,id)) continue;
        if(cnt==want){ hit=i; break; }
        cnt++;
    }
    if(hit<0){
        sb_put(text,"未找到匹配控件。当前可见语义节点(前10):\n");
        for(int i=0;i<n&&i<10;i++){
            sb_put(text,"  "); sb_json_str(text,arr[i].text);
            if(arr[i].id[0]){ sb_put(text," / "); sb_put(text,arr[i].id); }
            sb_putc(text,'\n');
        }
        free(arr); return;
    }
    PElem *el=&arr[hit];
    int cx=(el->x1+el->x2)/2, cy=(el->y1+el->y2)/2;
    char cmd[128]; snprintf(cmd,sizeof cmd,"input tap %d %d",cx,cy);
    SB o; sb_init(&o); int code;
    run_sh(cmd,10000,&o,&code); sb_free(&o);
    pilot_audit("pilot_tap",0,"tap",el->text[0]?el->text:el->id,code==0?"ok":"fail",0);
    sb_put(text,code==0?"已点击: ":"点击失败: ");
    sb_json_str(text,el->text[0]?el->text:el->id);
    char b[96]; snprintf(b,sizeof b," @(%d,%d) bounds[%d,%d][%d,%d]",cx,cy,el->x1,el->y1,el->x2,el->y2);
    sb_put(text,b);
    free(arr);
}

/* ---- 工具3：pilot_run 任务执行（技能回放/步骤序列） ---- */
static void pilot_run(SB *text, int *is_error){
    const char *skill=parg("skill"), *steps_arg=parg("steps");
    const char *save_as=parg("save_as"), *confirm=parg("confirm");
    int allow_sensitive = (confirm && (!strcmp(confirm,"1")||!strcmp(confirm,"true")));
    char *steps_raw=NULL; char *skill_file=NULL; int runs=0;
    const char *task = (skill&&*skill)?skill:(save_as&&*save_as?save_as:"adhoc");

    if(skill&&*skill){
        if(!pilot_name_ok(skill)){ sb_put(text,"非法技能名"); *is_error=1; return; }
        char path[512]; snprintf(path,sizeof path,PILOT_SKILLS "/%s.json",skill);
        size_t fl=0; skill_file=read_file(path,&fl);
        if(!skill_file){ sb_put(text,"技能不存在: "); sb_put(text,skill); *is_error=1; return; }
        const char *vs=NULL; size_t vl=0;
        if(!json_find(skill_file,skill_file+fl,"steps",&vs,&vl)){ sb_put(text,"技能文件缺少 steps"); free(skill_file); *is_error=1; return; }
        steps_raw=(char*)malloc(vl+1); memcpy(steps_raw,vs,vl); steps_raw[vl]=0;
        runs=json_int(skill_file,skill_file+fl,"runs",0);
    } else if(steps_arg&&*steps_arg){
        steps_raw=strdup(steps_arg);
    } else {
        sb_put(text,"需要 skill(回放) 或 steps(JSON步骤数组) 参数"); *is_error=1; return;
    }

    pvars_reset();
    long t0=now_ms();
    const char *arr=steps_raw; size_t al=strlen(steps_raw);
    const char *p=skip_ws(arr,arr+al);
    if(p>=arr+al||*p!='['){ sb_put(text,"steps 必须是 JSON 数组"); free(steps_raw); free(skill_file); *is_error=1; return; }
    p++;
    int si=0, ok_all=1;
    sb_put(text,"[pilot] 任务: "); sb_put(text,task); sb_putc(text,'\n');
    for(;;){
        p=skip_ws(p,arr+al);
        if(p>=arr+al||*p==']') break;
        const char *os=p;
        p=skip_value(p,arr+al);
        size_t ol=(size_t)(p-os);
        char act[64]="";
        pj_str(os,os+ol,"act",act,sizeof act);
        long s0=now_ms();
        char detail[512]=""; char result[32]="ok";
        int sensitive=0, ok=1;

        /* 敏感标记 */
        char sens[16]="";
        pj_str(os,os+ol,"sensitive",sens,sizeof sens);
        if(!strcmp(sens,"1")||!strcmp(sens,"true")) sensitive=1;
        if(!strcmp(act,"shell")) sensitive=1;

        if(!strcmp(act,"open_app")){
            char pkg[256]=""; pj_str(os,os+ol,"package",pkg,sizeof pkg);
            snprintf(detail,sizeof detail,"package=%s",pkg);
            if(!pilot_pkg_ok(pkg)){ ok=0; snprintf(result,sizeof result,"bad_package"); }
            else {
                char cmd[768]; snprintf(cmd,sizeof cmd,"ACT=$(cmd package resolve-activity --brief %s 2>/dev/null | tail -1); case \"$ACT\" in */*) am start -n \"$ACT\" >/dev/null 2>&1;; *) monkey -p %s -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1;; esac",pkg,pkg);
                SB o; sb_init(&o); int code; run_sh(cmd,15000,&o,&code); sb_free(&o);
                ok=(code==0); if(!ok) snprintf(result,sizeof result,"exit=%d",code);
                usleep(1200000);
            }
        } else if(!strcmp(act,"tap_text")||!strcmp(act,"tap_id")){
            char key[256]=""; pj_str(os,os+ol,!strcmp(act,"tap_text")?"text":"id",key,sizeof key);
            snprintf(detail,sizeof detail,"%s",key);
            char err[256]=""; PElem *ua=NULL;
            int n=pilot_load_ui(&ua,err,sizeof err);
            if(n<0){ ok=0; snprintf(result,sizeof result,"dump_fail"); }
            else {
                int hit=-1;
                for(int i=0;i<n;i++){
                    int m = !strcmp(act,"tap_text") ? (strstr(ua[i].text,key)!=NULL) : (strstr(ua[i].id,key)!=NULL);
                    if(m){ hit=i; break; }
                }
                if(hit<0){ ok=0; snprintf(result,sizeof result,"not_found"); }
                else {
                    int cx=(ua[hit].x1+ua[hit].x2)/2, cy=(ua[hit].y1+ua[hit].y2)/2;
                    char cmd[128]; snprintf(cmd,sizeof cmd,"input tap %d %d",cx,cy);
                    SB o; sb_init(&o); int code; run_sh(cmd,10000,&o,&code); sb_free(&o);
                    ok=(code==0);
                    snprintf(detail,sizeof detail,"%s @(%d,%d)",key,cx,cy);
                }
                free(ua);
            }
        } else if(!strcmp(act,"tap_xy")){
            int x=json_int(os,os+ol,"x",0), y=json_int(os,os+ol,"y",0);
            snprintf(detail,sizeof detail,"(%d,%d)",x,y);
            char cmd[128]; snprintf(cmd,sizeof cmd,"input tap %d %d",x,y);
            SB o; sb_init(&o); int code; run_sh(cmd,10000,&o,&code); sb_free(&o);
            ok=(code==0);
        } else if(!strcmp(act,"swipe")){
            int x1=json_int(os,os+ol,"x1",0),y1=json_int(os,os+ol,"y1",0),
                x2=json_int(os,os+ol,"x2",0),y2=json_int(os,os+ol,"y2",0),
                ms=json_int(os,os+ol,"ms",300);
            snprintf(detail,sizeof detail,"(%d,%d)->(%d,%d)",x1,y1,x2,y2);
            char cmd[192]; snprintf(cmd,sizeof cmd,"input swipe %d %d %d %d %d",x1,y1,x2,y2,ms);
            SB o; sb_init(&o); int code; run_sh(cmd,15000,&o,&code); sb_free(&o);
            ok=(code==0);
        } else if(!strcmp(act,"key")){
            int code=json_int(os,os+ol,"code",4);
            snprintf(detail,sizeof detail,"keycode=%d",code);
            char cmd[96]; snprintf(cmd,sizeof cmd,"input keyevent %d",code);
            SB o; sb_init(&o); int rc; run_sh(cmd,10000,&o,&rc); sb_free(&o);
            ok=(rc==0);
        } else if(!strcmp(act,"text")){
            char buf[2048]=""; pj_str(os,os+ol,"text",buf,sizeof buf);
            snprintf(detail,sizeof detail,"%.200s",buf);
            SB cmd; sb_init(&cmd); pilot_input_text(buf,&cmd);
            SB o; sb_init(&o); int rc; run_sh(cmd.p,10000,&o,&rc); sb_free(&o); sb_free(&cmd);
            ok=(rc==0);
        } else if(!strcmp(act,"wait")){
            int ms=json_int(os,os+ol,"ms",1000);
            if(ms<0) ms=0; if(ms>60000) ms=60000;
            snprintf(detail,sizeof detail,"%dms",ms);
            usleep((useconds_t)ms*1000);
        } else if(!strcmp(act,"assert_text")){
            char key[256]=""; pj_str(os,os+ol,"text",key,sizeof key);
            snprintf(detail,sizeof detail,"assert:%s",key);
            char err[256]=""; PElem *ua=NULL;
            int n=pilot_load_ui(&ua,err,sizeof err);
            if(n<0){ ok=0; snprintf(result,sizeof result,"dump_fail"); }
            else {
                int found=0;
                for(int i=0;i<n;i++) if(strstr(ua[i].text,key)){ found=1; break; }
                ok=found; if(!ok) snprintf(result,sizeof result,"assert_fail");
                free(ua);
            }
        } else if(!strcmp(act,"write_file")){
            char path[512]="", content[8192]="";
            pj_str(os,os+ol,"path",path,sizeof path);
            pj_str(os,os+ol,"content",content,sizeof content);
            snprintf(detail,sizeof detail,"path=%s bytes=%zu",path,strlen(content));
            if(strncmp(path,PILOT_DIR,strlen(PILOT_DIR))!=0 &&
               strncmp(path,"/sdcard/",8)!=0 &&
               strncmp(path,"/storage/emulated/0/",20)!=0) sensitive=1;
            if(!sensitive || allow_sensitive){
                FILE *f=fopen(path,"w");
                if(!f){ ok=0; snprintf(result,sizeof result,"open_fail"); }
                else { fwrite(content,1,strlen(content),f); fclose(f); }
            }
        } else if(!strcmp(act,"shell")){
            char cmd[4096]=""; pj_str(os,os+ol,"cmd",cmd,sizeof cmd);
            snprintf(detail,sizeof detail,"%.300s",cmd);
            if(allow_sensitive){
                SB o; sb_init(&o); int rc; run_sh(cmd,60000,&o,&rc);
                ok=(rc==0); if(!ok) snprintf(result,sizeof result,"exit=%d",rc);
                if(o.len){ size_t tl=o.len>200?200:o.len; strncat(detail," | ",sizeof detail-strlen(detail)-1); strncat(detail,o.p,tl); }
                sb_free(&o);
            }
        } else if(!strcmp(act,"stop_app")){
            char pkg[256]=""; pj_str(os,os+ol,"package",pkg,sizeof pkg);
            snprintf(detail,sizeof detail,"package=%s",pkg);
            if(!pilot_pkg_ok(pkg)){ ok=0; snprintf(result,sizeof result,"bad_package"); }
            else {
                char cmd[384]; snprintf(cmd,sizeof cmd,"am force-stop %s",pkg);
                SB o; sb_init(&o); int code; run_sh(cmd,15000,&o,&code); sb_free(&o);
                ok=(code==0); if(!ok) snprintf(result,sizeof result,"exit=%d",code);
                usleep(500000);
            }
        } else if(!strcmp(act,"capture")){
            char var[32]="", source[16]="ui", kw[256]="", rx[512]="", path[512]="", prop[128]="", cmd[2048]="";
            pj_str(os,os+ol,"var",var,sizeof var);
            pj_str(os,os+ol,"source",source,sizeof source);
            pj_str(os,os+ol,"keyword",kw,sizeof kw);
            pj_str(os,os+ol,"regex",rx,sizeof rx);
            char *val=NULL;
            if(!strcmp(source,"ui")){
                char pick[16]="first";
                pj_str(os,os+ol,"pick",pick,sizeof pick);
                char err[256]=""; PElem *ua=NULL;
                int n=pilot_load_ui(&ua,err,sizeof err);
                if(n<0){ ok=0; snprintf(result,sizeof result,"dump_fail"); }
                else {
                    double bestv=0;
                    for(int i=0;i<n;i++){
                        if(kw[0] && !strstr(ua[i].text,kw)) continue;
                        char *m=NULL;
                        if(rx[0]) m=pregex_extract(rx,ua[i].text);
                        else if(kw[0]) m=strdup(ua[i].text);
                        if(!m) continue;
                        if(!strcmp(pick,"max")){
                            double v=atof(m);
                            if(!val||v>bestv){ free(val); val=m; bestv=v; } else free(m);
                        } else if(!strcmp(pick,"min")){
                            double v=atof(m);
                            if(!val||v<bestv){ free(val); val=m; bestv=v; } else free(m);
                        } else if(!strcmp(pick,"last")){
                            free(val); val=m;
                        } else {
                            if(!val) val=m; else free(m);
                        }
                    }
                    free(ua);
                    if(!val && ok){ ok=0; snprintf(result,sizeof result,"no_match"); }
                }
            } else if(!strcmp(source,"file")){
                pj_str(os,os+ol,"path",path,sizeof path);
                snprintf(detail,sizeof detail,"file=%.200s",path);
                size_t fl=0; char *fc=read_file(path,&fl);
                if(!fc){ ok=0; snprintf(result,sizeof result,"read_fail"); }
                else {
                    if(rx[0]) val=pregex_extract(rx,fc);
                    else { size_t vl=fl>500?500:fl; val=(char*)malloc(vl+1); if(val){ memcpy(val,fc,vl); val[vl]=0; } }
                    free(fc);
                    if(!val && ok){ ok=0; snprintf(result,sizeof result,"no_match"); }
                }
            } else if(!strcmp(source,"prop")){
                pj_str(os,os+ol,"prop",prop,sizeof prop);
                int bad=0;
                for(const char *q=prop;*q;q++)
                    if(!(isalnum((unsigned char)*q)||*q=='.'||*q=='_'||*q=='-')) bad=1;
                if(!prop[0]||bad){ ok=0; snprintf(result,sizeof result,"bad_prop"); }
                else {
                    char c[256]; snprintf(c,sizeof c,"getprop %s",prop);
                    SB o; sb_init(&o); int rc; run_sh(c,10000,&o,&rc);
                    if(rc==0&&o.len){ char *nl=strchr(o.p,'\n'); if(nl)*nl=0; val=strdup(o.p); }
                    else { ok=0; snprintf(result,sizeof result,"prop_fail"); }
                    sb_free(&o);
                }
            } else if(!strcmp(source,"shell")){
                sensitive=1;
                pj_str(os,os+ol,"cmd",cmd,sizeof cmd);
                snprintf(detail,sizeof detail,"shell=%.200s",cmd);
                if(allow_sensitive){
                    SB o; sb_init(&o); int rc; run_sh(cmd,30000,&o,&rc);
                    if(rc==0&&o.len){
                        if(rx[0]) val=pregex_extract(rx,o.p);
                        else { char *nl=strchr(o.p,'\n'); if(nl)*nl=0; val=strdup(o.p); }
                    }
                    if(!val && ok){ ok=0; snprintf(result,sizeof result,"no_output"); }
                    sb_free(&o);
                }
            } else { ok=0; snprintf(result,sizeof result,"bad_source"); }
            if(!var[0]){ ok=0; snprintf(result,sizeof result,"need_var"); }
            if(val){
                pvar_set(var,val);
                snprintf(detail,sizeof detail,"%s=%.200s",var,val);
                free(val);
            }
        } else if(!strcmp(act,"assert_regex")){
            char rx[512]=""; pj_str(os,os+ol,"regex",rx,sizeof rx);
            snprintf(detail,sizeof detail,"assert_regex:%.200s",rx);
            char err[256]=""; PElem *ua=NULL;
            int n=pilot_load_ui(&ua,err,sizeof err);
            if(n<0){ ok=0; snprintf(result,sizeof result,"dump_fail"); }
            else {
                int found=0;
                for(int i=0;i<n && !found;i++){
                    char *m=pregex_extract(rx,ua[i].text);
                    if(m){ found=1; free(m); }
                }
                ok=found; if(!ok) snprintf(result,sizeof result,"assert_fail");
                free(ua);
            }
        } else {
            ok=0; snprintf(result,sizeof result,"unknown_act");
            snprintf(detail,sizeof detail,"act=%s",act);
        }

        /* 敏感动作未确认 → 暂停（此前步骤已执行并审计） */
        if(sensitive && !allow_sensitive && ok){
            pilot_audit(task,si,act,detail,"NEED_CONFIRM",now_ms()-s0);
            sb_put(text,"⏸ 步骤"); sb_puti(text,si); sb_put(text," 为敏感动作("); sb_put(text,act);
            sb_put(text,")，需确认后继续：pilot_run 带 confirm=1 重新提交。详情: ");
            sb_put(text,detail); sb_putc(text,'\n');
            *is_error=1; ok_all=0;
            free(steps_raw); free(skill_file);
            return;
        }

        pilot_audit(task,si,act,detail,result,now_ms()-s0);
        sb_put(text,"  step"); sb_puti(text,si); sb_putc(text,' '); sb_put(text,act);
        sb_put(text," → "); sb_put(text,result); sb_putc(text,'\n');
        si++;
        if(!ok){ ok_all=0; sb_put(text,"任务在步骤"); sb_puti(text,si-1); sb_put(text,"失败中止\n"); break; }
        p=skip_ws(p,arr+al);
        if(p<arr+al&&*p==',') p++;
    }
    long elapsed=now_ms()-t0;
    if(ok_all){
        if(save_as&&*save_as&&pilot_name_ok(save_as)){
            if(pilot_write_skill(save_as,steps_raw,1)){
                sb_put(text,"✔ 技能已结晶: "); sb_put(text,save_as); sb_putc(text,'\n');
            }
        } else if(skill&&*skill){
            pilot_write_skill(skill,steps_raw,runs+1);
            sb_put(text,"✔ 技能回放成功: "); sb_put(text,skill);
            sb_put(text," (累计 "); sb_puti(text,runs+1); sb_put(text," 次)\n");
        }
        for(int i=0;i<g_npvars;i++){
            sb_put(text,"  var "); sb_put(text,g_pvars[i].name);
            sb_putc(text,'='); sb_put(text,g_pvars[i].val); sb_putc(text,'\n');
        }
        sb_put(text,"总耗时 "); sb_puti(text,elapsed); sb_put(text," ms\n");
    } else {
        *is_error=1;
        sb_put(text,"总耗时 "); sb_puti(text,elapsed); sb_put(text," ms（审计已留痕）\n");
    }
    free(steps_raw); free(skill_file);
}

/* ---- 工具4：pilot_skills 技能库管理 ---- */
static void pilot_skills(SB *text){
    const char *action=parg("action"); const char *name=parg("name");
    if(!action) action="list";
    if(!strcmp(action,"list")){
        DIR *d=opendir(PILOT_SKILLS);
        if(!d){ sb_put(text,"技能库为空"); return; }
        struct dirent *de; int n=0;
        while((de=readdir(d))){
            size_t l=strlen(de->d_name);
            if(l<6||strcmp(de->d_name+l-5,".json")!=0) continue;
            char path[768]; snprintf(path,sizeof path,PILOT_SKILLS "/%s",de->d_name);
            size_t fl=0; char *j=read_file(path,&fl);
            int runs=j?json_int(j,j+fl,"runs",0):0;
            char nm[256]=""; if(j) json_str(j,j+fl,"name",nm,sizeof nm);
            sb_put(text,nm[0]?nm:de->d_name);
            sb_put(text,"  runs="); sb_puti(text,runs); sb_putc(text,'\n');
            free(j); n++;
        }
        closedir(d);
        if(!n) sb_put(text,"技能库为空");
        else { sb_put(text,"共 "); sb_puti(text,n); sb_put(text," 个技能"); }
    } else if(!strcmp(action,"show")){
        if(!pilot_name_ok(name)){ sb_put(text,"非法技能名"); return; }
        char path[512]; snprintf(path,sizeof path,PILOT_SKILLS "/%s.json",name);
        size_t fl=0; char *j=read_file(path,&fl);
        if(!j){ sb_put(text,"技能不存在"); return; }
        sb_putn(text,j,fl); free(j);
    } else if(!strcmp(action,"del")){
        if(!pilot_name_ok(name)){ sb_put(text,"非法技能名"); return; }
        char path[512]; snprintf(path,sizeof path,PILOT_SKILLS "/%s.json",name);
        if(unlink(path)==0){ sb_put(text,"已删除技能: "); sb_put(text,name); }
        else sb_put(text,"删除失败(不存在?)");
    } else sb_put(text,"action 须为 list/show/del");
}

/* ---- 工具5：pilot_audit 审计查询 ---- */
static void pilot_audit_view(SB *text){
    int lines=parg_int("lines",20);
    if(lines<1) lines=1; if(lines>200) lines=200;
    char path[256]; time_t t=time(NULL); struct tm tm; localtime_r(&t,&tm);
    snprintf(path,sizeof path,PILOT_AUDIT "/pilot-%04d%02d%02d.jsonl",tm.tm_year+1900,tm.tm_mon+1,tm.tm_mday);
    size_t fl=0; char *j=read_file(path,&fl);
    if(!j){ sb_put(text,"今日暂无审计记录"); return; }
    /* 定位最后 N 行 */
    int nl=0; char *start=j;
    for(char *p=j+fl;p>j;p--){
        if(*p=='\n'){ nl++; if(nl>lines){ start=p+1; break; } }
    }
    sb_put(text,start);
    free(j);
}

/* ---- 内建分发 ---- */
static void pilot_dispatch(const char *name, SB *out, const char *idraw){
    SB text; sb_init(&text);
    int is_error=0;
    pilot_dirs();
    if(!strcmp(name,"pilot_perceive")) pilot_perceive(&text);
    else if(!strcmp(name,"pilot_tap")) pilot_tap(&text);
    else if(!strcmp(name,"pilot_run")) pilot_run(&text,&is_error);
    else if(!strcmp(name,"pilot_skills")) pilot_skills(&text);
    else if(!strcmp(name,"pilot_audit")) pilot_audit_view(&text);
    else { sb_put(&text,"未知内建工具"); is_error=1; }
    if(is_error) M->failures++;
    sb_result_text(out,idraw,text.p,is_error);
    sb_free(&text);
}

static void pilot_register(void){
    pilot_dirs();
    reg_add("pilot_perceive","LemonPilot",
        "[LemonPilot] 语义感知：dump控件树解析为语义元素表(text/id/bounds/clickable)，关键词过滤，数据优先视觉兜底",
        "keyword:string:过滤关键词(匹配text或id,空=全部)|limit:integer:最大条数默认40",
        "@builtin:pilot_perceive",30000);
    reg_add("pilot_tap","LemonPilot",
        "[LemonPilot] 语义点击：按text/id定位控件并点击中心(自动重新感知，免坐标硬编码)",
        "text:string:控件文本子串|id:string:resource-id子串|index:integer:第几个匹配默认0",
        "@builtin:pilot_tap",30000);
    reg_add("pilot_run","LemonPilot",
        "[LemonPilot] 任务执行：回放技能或执行JSON步骤序列(open_app/stop_app/tap_text/tap_id/tap_xy/swipe/key/text/wait/assert_text/assert_regex/capture/write_file/shell)，capture捕获UI/文件/属性/shell数据到变量(UI支持pick=first/last/max/min择值)，后续步骤{{var}}动态绑定，敏感动作需confirm=1，全程JSONL审计，成功自动结晶技能",
        "skill:string:技能名(回放)|steps:string:JSON步骤数组(新任务)|save_as:string:成功后结晶为技能名|confirm:string:1=允许敏感动作",
        "@builtin:pilot_run",180000);
    reg_add("pilot_skills","LemonPilot",
        "[LemonPilot] 技能库：list/show/del 已结晶技能(成功路径沉淀，回放零Token)",
        "action:string:list或show或del|name:string:技能名",
        "@builtin:pilot_skills",15000);
    reg_add("pilot_audit","LemonPilot",
        "[LemonPilot] 审计日志：查看最近动作审计记录(JSONL，含敏感动作暂停留痕)",
        "lines:integer:条数默认20",
        "@builtin:pilot_audit",15000);
}

static void jsonrpc_handle(const char *body, size_t blen, SB *out, int *has_resp){
    const char *e = body+blen;
    char method[128] = "";
    char idraw[128] = "";
    json_raw(body,e,"id",idraw,sizeof idraw);
    json_str(body,e,"method",method,sizeof method);
    M->requests++;
    *has_resp = 1;
    {
        const char *w = skip_ws(body,e);
        if(w>=e || *w!='{'){ sb_error(out,idraw,-32700,"parse error: not a JSON object"); return; }
    }

    if(strcmp(method,"initialize")==0){
        char clientproto[64]="";
        {
            const char *pvv=NULL; size_t pvl=0;
            if(json_find(body,e,"params",&pvv,&pvl))
                json_str(pvv,pvv+pvl,"protocolVersion",clientproto,sizeof clientproto);
            if(!clientproto[0]) json_str(body,e,"protocolVersion",clientproto,sizeof clientproto);
        }
        int total = TN + up_unique_count();
        char sid[40];
        snprintf(sid,sizeof sid,"%lx",(unsigned long)(now_ms()));
        sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":"); sb_put(out, idraw[0]?idraw:"null");
        const char *pvReply = PROTOCOL_VERSION;
        if(clientproto[0] && (!strcmp(clientproto,"2024-11-05") || !strcmp(clientproto,"2025-03-26") || !strcmp(clientproto,"2025-06-18"))) pvReply = clientproto;
        sb_put(out,",\"result\":{\"protocolVersion\":"); sb_json_str(out,pvReply);
        sb_put(out,",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":"); sb_json_str(out,CORE_NAME);
        sb_put(out,",\"version\":"); sb_json_str(out,CORE_VER);
        sb_put(out,"},\"tools\":"); sb_puti(out,total);
        sb_put(out,",\"local_tools\":"); sb_puti(out,TN);
        sb_put(out,",\"sessionId\":"); sb_json_str(out,sid);
        sb_put(out,",\"clientProtocol\":"); sb_json_str(out,clientproto);
        sb_put(out,",\"protocolMatched\":");
        sb_put(out, (clientproto[0]==0 || strcmp(clientproto,pvReply)==0) ? "true":"false");
        sb_put(out,"}}");
        return;
    }
    if(strcmp(method,"notifications/initialized")==0){ *has_resp=0; return; }
    if(strcmp(method,"ping")==0){
        if(idraw[0]){
            sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":"); sb_put(out,idraw);
            sb_put(out,",\"result\":{}}");
        } else *has_resp=0;
        return;
    }
    if(strcmp(method,"tools/list")==0){
        SB tl; sb_init(&tl);
        int _all=0;
        {
            const char *pv=NULL; size_t pl=0; char alls[16]="";
            if(json_find(body,e,"params",&pv,&pl)) json_str(pv,pv+pl,"all",alls,sizeof alls);
            _all = (alls[0]=='1'||alls[0]=='t'||alls[0]=='T');
            if(!_all){
                const char *ap = strstr(body,"\"all\"");
                if(ap && ap<e){
                    ap += 5;
                    while(*ap==' ') ap++;
                    if(*ap==':'){ ap++; while(*ap==' ') ap++; }
                    if(!strncmp(ap,"true",4)||*ap=='1') _all=1;
                }
            }
        }
        sb_tools_list(&tl,_all);
        sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":"); sb_put(out,idraw[0]?idraw:"null");
        sb_put(out,",\"result\":");
        sb_putn(out,tl.p,tl.len);
        sb_put(out,"}");
        sb_free(&tl);
        return;
    }
    if(strcmp(method,"tools/call")==0){
        char name[512]="";
        const char *pv=NULL; size_t pl=0;
        const char *ar=NULL; size_t al=0;
        if(json_find(body,e,"params",&pv,&pl)){
            json_str(pv,pv+pl,"name",name,sizeof name);
            json_find(pv,pv+pl,"arguments",&ar,&al);
        }
        M->calls++;
        /* #418：每次调用重置分流上下文；maxChars>0 覆盖，=0 全量，缺省 4096 */
        snprintf(g_slim_tool, sizeof g_slim_tool, "%s", name);
        g_slim_max = (ar && al>1) ? json_find_num(ar, al, "maxChars") : -1;
        Tool *t = reg_find(name);
        if(t){
            if(ar && al>1) args_collect(ar,al); else args_reset();
            if(strncmp(t->cmd,"@builtin:",9)==0){
                pilot_dispatch(t->cmd+9,out,idraw);
                args_reset();
                return;
            }
            SB cmd; sb_init(&cmd);
            subst_cmd(t->cmd,&cmd);
            SB o; sb_init(&o);
            int code;
            run_sh(cmd.p,t->timeout,&o,&code);
            sb_free(&cmd);
            args_reset();
            if(code<0){
                M->failures++;
                sb_result_text(out,idraw,o.p?o.p:"exec failed",1);
            } else {
                SB text; sb_init(&text);
                sb_put(&text,"[exit="); sb_puti(&text,code); sb_put(&text,"]\n");
                if(o.len) sb_putn(&text,o.p,o.len);
                sb_result_text(out,idraw,text.p,0);
                sb_free(&text);
            }
            sb_free(&o);
            return;
        }
        if(UP_ENABLED){
            SB text; sb_init(&text);
            int rc = up_call(name, ar, al, &text, UP_TIMEOUT);
            if(rc==0) sb_result_text(out,idraw,text.p,0);
            else if(rc==2){ M->failures++; sb_result_text(out,idraw,text.p,1); }
            else {
                M->failures++;
                char m[600];
                snprintf(m,sizeof m,"unknown tool: %s (本地 %d 个工具，上游也不可用)",name,TN);
                sb_result_text(out,idraw,m,1);
            }
            sb_free(&text);
            return;
        }
        {
            char m[256];
            snprintf(m,sizeof m,"unknown tool: %s (本地 %d 个工具)",name,TN);
            sb_result_text(out,idraw,m,1);
        }
        return;
    }
    if(strcmp(method,"kernel/reload")==0){
        int before = TN;
        reg_clear();
        registry_load();
        FILE *f = fopen(RELOAD_FLAG,"w");
        if(f){ fputs("1",f); fclose(f); }
        sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":"); sb_put(out,idraw[0]?idraw:"null");
        sb_put(out,",\"result\":{\"removed\":0,\"total\":"); sb_puti(out,TN);
        sb_put(out,",\"report\":[\"小柠檬内核 reload: ");
        sb_puti(out,TN);
        sb_put(out," tools (was ");
        sb_puti(out,before);
        sb_put(out,")\"]}}");
        return;
    }
    if(strcmp(method,"kernel/upstream")==0){
        sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":"); sb_put(out,idraw[0]?idraw:"null");
        sb_put(out,",\"result\":{\"enabled\":"); sb_put(out,UP_ENABLED?"true":"false");
        sb_put(out,",\"host\":"); sb_json_str(out,UP_HOST);
        sb_put(out,",\"port\":"); sb_puti(out,UP_PORT);
        sb_put(out,",\"local_tools\":"); sb_puti(out,TN);
        sb_put(out,"}}");
        return;
    }
    if(strcmp(method,"kernel/health")==0){
        char hb[512];
        long up = (now_ms()-M->start_ms)/1000;
        snprintf(hb,sizeof hb,
            "[小柠檬内核] v%s uptime=%lds tools=%d conns=%ld reqs=%ld calls=%ld failures=%ld upstream=%ld",
            CORE_VER,up,TN,M->conns,M->requests,M->calls,M->failures,M->upstream);
        sb_put(out,"{\"jsonrpc\":\"2.0\",\"id\":"); sb_put(out,idraw[0]?idraw:"null");
        sb_put(out,",\"result\":{\"content\":[{\"type\":\"text\",\"text\":");
        sb_json_str(out,hb);
        sb_put(out,"}],\"isError\":false}}");
        return;
    }
    /* 未知/缺失方法：与 Java 一致 —— 无 id 视为通知不回，有 id 才回 -32601 */
    if(!idraw[0]){ *has_resp = 0; return; }
    {
        char mm[256];
        snprintf(mm,sizeof mm,"method not found: %s",method);
        sb_error(out,idraw,-32601,mm);
    }
}
/* ============================================================
 * HTTP 层 —— 与 Java 内核行为严格对齐
 *   · 每响应 Connection: close 并立即关连接（Java 亦然，客户端按 EOF 读）
 *   · Content-Type: application/json; charset=utf-8 + CORS 三头
 *   · 路由: OPTIONS / GET /healthz* /metrics /version / POST / 其他 404
 *   · Expect: 100-continue 支持（大 body 客户端友好）
 *   · 增量式 chunked 解码，精确终止块判定
 *   · 连接 30s 读超时（对齐 Java setSoTimeout(30000)）
 * ============================================================ */
static const char *status_msg(int code){
    switch(code){
        case 200: return "OK";
        case 202: return "Accepted";
        case 204: return "No Content";
        case 404: return "Not Found";
        case 405: return "Method Not Allowed";
        case 413: return "Payload Too Large";
        case 503: return "Service Unavailable";
        default:  return "Error";
    }
}

static void respond(int fd, int code, const char *body, size_t blen){
    char head[512];
    int hlen = snprintf(head,sizeof head,
        "HTTP/1.1 %d %s\r\n"
        "Content-Type: application/json; charset=utf-8\r\n"
        "Content-Length: %zu\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n"
        "Access-Control-Allow-Headers: Content-Type\r\n"
        "Connection: close\r\n\r\n",
        code, status_msg(code), blen);
    if(hlen>0) write_all(fd,head,(size_t)hlen);
    if(blen) write_all(fd,body,blen);
}

/* 增量式 chunked 解码：0=完整, -1=数据未齐/格式错误 */
static int try_decode_chunked(const char *b, size_t n, SB *out){
    const char *p = b, *e = b+n;
    for(;;){
        const char *le = (const char*)memchr(p,'\n',(size_t)(e-p));
        if(!le) return -1;
        char szb[24];
        size_t sl = (size_t)(le-p);
        if(sl && p[sl-1]=='\r') sl--;
        if(sl==0 || sl>=sizeof szb) return -1;
        memcpy(szb,p,sl); szb[sl]=0;
        char *semi = strchr(szb,';');
        if(semi) *semi=0;
        char *endp = NULL;
        unsigned long csz = strtoul(szb,&endp,16);
        if(endp==szb || *endp) return -1;
        p = le+1;
        if(csz==0){
            if((size_t)(e-p)>=2 && p[0]=='\r' && p[1]=='\n') p+=2;
            else if((size_t)(e-p)>=1 && p[0]=='\n') p++;
            return 0;
        }
        if((size_t)(e-p) < csz) return -1;
        sb_putn(out,p,csz);
        p += csz;
        if((size_t)(e-p)>=2 && p[0]=='\r' && p[1]=='\n') p+=2;
        else if((size_t)(e-p)>=1 && p[0]=='\n') p++;
        else return -1;
    }
}

/* 读到 header 结束，返回 header 长度；0=失败 */
static size_t read_headers(int fd, SB *in){
    for(;;){
        char *sep = strstr(in->p,"\r\n\r\n");
        if(sep) return (size_t)(sep - in->p);
        if(in->len > (1u*1024*1024)) return 0;
        char buf[16384];
        ssize_t n = read(fd,buf,sizeof buf);
        if(n<0){ if(errno==EINTR) continue; return 0; }
        if(n==0) return 0;
        sb_putn(in,buf,(size_t)n);
    }
}

static void handle_conn(int fd){
    SB in; sb_init(&in);
    size_t hlen = read_headers(fd,&in);
    if(!hlen){ sb_free(&in); return; }
    size_t boff = hlen + 4;

    char mline[1024];
    size_t i;
    for(i=0;i<hlen && i<sizeof mline-1; i++){
        if(in.p[i]=='\r' || in.p[i]=='\n') break;
        mline[i] = in.p[i];
    }
    mline[i] = 0;
    char method[16] = "", path[1024] = "";
    {
        char *sp = strchr(mline,' ');
        if(!sp){ sb_free(&in); return; }
        *sp = 0;
        snprintf(method,sizeof method,"%s",mline);
        char *sp2 = strchr(sp+1,' ');
        if(sp2) *sp2 = 0;
        snprintf(path,sizeof path,"%s", sp+1);
    }
    size_t clen = 0; int chunked = 0, expect100 = 0;
    {
        const char *p = in.p, *e = in.p + hlen;
        while(p < e){
            const char *le = (const char*)memchr(p,'\n',(size_t)(e-p));
            if(!le) le = e;
            size_t ll = (size_t)(le-p);
            if(ll && p[ll-1]=='\r') ll--;
            if(ll>14 && strncasecmp(p,"Content-Length:",15)==0){
                char b[32]; size_t t = ll-15<31?ll-15:31;
                memcpy(b,p+15,t); b[t]=0;
                clen = (size_t)strtoul(b,NULL,10);
            }
            if(ll>17 && strncasecmp(p,"Transfer-Encoding:",18)==0 && ci_contains(p+18,ll-18,"chunked")) chunked = 1;
            if(ll>6 && strncasecmp(p,"Expect:",7)==0 && ci_contains(p+7,ll-7,"100-continue")) expect100 = 1;
            p = le+1;
        }
    }

    if(strcmp(method,"OPTIONS")==0){ respond(fd,204,"",0); sb_free(&in); return; }

    if(strcmp(method,"GET")==0){
        SB o; sb_init(&o);
        if(strncmp(path,"/healthz",8)==0){
            sb_put(&o,"{\"status\":\"ok\",\"server\":"); sb_json_str(&o,CORE_NAME);
            sb_put(&o,",\"version\":"); sb_json_str(&o,CORE_VER);
            sb_put(&o,",\"protocol\":"); sb_json_str(&o,PROTOCOL_VERSION);
            sb_put(&o,",\"tools\":"); sb_puti(&o,TN);
            sb_put(&o,",\"upstream\":"); sb_put(&o,UP_ENABLED?"true":"false");
            sb_put(&o,"}");
            respond(fd,200,o.p,o.len);
        } else if(strncmp(path,"/metrics",8)==0){
            sb_put(&o,"{\"server\":"); sb_json_str(&o,CORE_NAME);
            sb_put(&o,",\"version\":"); sb_json_str(&o,CORE_VER);
            sb_put(&o,",\"protocol\":"); sb_json_str(&o,PROTOCOL_VERSION);
            sb_put(&o,",\"uptime_ms\":"); sb_puti(&o, now_ms()-M->start_ms);
            sb_put(&o,",\"requests\":"); sb_puti(&o, M->requests);
            sb_put(&o,",\"failures\":"); sb_puti(&o, M->failures);
            sb_put(&o,",\"busy\":0,\"avg_ms\":0");
            sb_put(&o,",\"tools\":"); sb_puti(&o,TN);
            sb_put(&o,",\"local_tools\":"); sb_puti(&o,TN);
            sb_put(&o,",\"calls\":"); sb_puti(&o, M->calls);
            sb_put(&o,",\"upstream_calls\":"); sb_puti(&o, M->upstream);
            sb_put(&o,",\"conns\":"); sb_puti(&o, M->conns);
            sb_put(&o,"}");
            respond(fd,200,o.p,o.len);
        } else if(strncmp(path,"/version",8)==0){
            sb_put(&o,"{\"server\":"); sb_json_str(&o,CORE_NAME);
            sb_put(&o,",\"version\":"); sb_json_str(&o,CORE_VER);
            sb_put(&o,",\"protocol\":"); sb_json_str(&o,PROTOCOL_VERSION);
            sb_put(&o,"}");
            respond(fd,200,o.p,o.len);
        } else {
            respond(fd,404,"",0);
        }
        sb_free(&o); sb_free(&in);
        return;
    }

    if(strcmp(method,"POST")!=0){ respond(fd,404,"",0); sb_free(&in); return; }

    if(!chunked && clen > MAX_BODY){
        const char *e413 = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"body too large\"}}";
        respond(fd,413,e413,strlen(e413));
        sb_free(&in);
        return;
    }
    if(expect100) write_all(fd,"HTTP/1.1 100 Continue\r\n\r\n",25);

    SB body; sb_init(&body);
    if(chunked){
        while(try_decode_chunked(in.p+boff, in.len-boff, &body) != 0){
            if(in.len > MAX_BODY) break;
            char buf[16384];
            ssize_t n = read(fd,buf,sizeof buf);
            if(n<0){ if(errno==EINTR) continue; break; }
            if(n==0) break;
            sb_putn(&in,buf,(size_t)n);
        }
    } else {
        while(in.len - boff < clen){
            char buf[16384];
            ssize_t n = read(fd,buf,sizeof buf);
            if(n<0){ if(errno==EINTR) continue; break; }
            if(n==0) break;
            sb_putn(&in,buf,(size_t)n);
            if(in.len > MAX_BODY) break;
        }
        size_t avail = (in.len > boff) ? (in.len-boff) : 0;
        sb_putn(&body, in.p+boff, avail<clen?avail:clen);
    }
    if(body.len > MAX_BODY){
        const char *e413 = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"body too large\"}}";
        respond(fd,413,e413,strlen(e413));
        sb_free(&body); sb_free(&in);
        return;
    }

    SB out; sb_init(&out);
    int has_resp = 1;
    if(body.len == 0){
        sb_put(&out,"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error: empty body\"}}");
    } else {
        jsonrpc_handle(body.p, body.len, &out, &has_resp);
    }
    if(has_resp && out.len) respond(fd,200,out.p,out.len);
    else respond(fd,202,"",0);
    sb_free(&out); sb_free(&body); sb_free(&in);
}

/* ============================================================
 * 主循环：fork-per-connection（崩溃隔离）+ v4/v6 双栈回环监听
 * ============================================================ */
static int listen_v4(int port){
    int fd = socket(AF_INET,SOCK_STREAM,0);
    if(fd<0) return -1;
    int one = 1;
    setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof one);
    fcntl(fd,F_SETFD,FD_CLOEXEC);
    struct sockaddr_in sa; memset(&sa,0,sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    sa.sin_port = htons((uint16_t)port);
    if(bind(fd,(struct sockaddr*)&sa,sizeof sa)<0){ close(fd); return -1; }
    if(listen(fd,512)<0){ close(fd); return -1; }
    return fd;
}

static int listen_v6(int port){
    int fd = socket(AF_INET6,SOCK_STREAM,0);
    if(fd<0) return -1;
    int one = 1;
    setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof one);
    setsockopt(fd,IPPROTO_IPV6,IPV6_V6ONLY,&one,sizeof one);
    fcntl(fd,F_SETFD,FD_CLOEXEC);
    struct sockaddr_in6 sa; memset(&sa,0,sizeof sa);
    sa.sin6_family = AF_INET6;
    sa.sin6_addr = in6addr_loopback;
    sa.sin6_port = htons((uint16_t)port);
    if(bind(fd,(struct sockaddr*)&sa,sizeof sa)<0){ close(fd); return -1; }
    if(listen(fd,512)<0){ close(fd); return -1; }
    return fd;
}

int main(int argc, char **argv){
    for(int _ai=1;_ai<argc;_ai++){
        if(!strcmp(argv[_ai],"--version")||!strcmp(argv[_ai],"-v")){
            printf("lemoncore v%s native MCP kernel (aarch64)\n",CORE_VER);
            return 0;
        }
        if(!strcmp(argv[_ai],"--help")||!strcmp(argv[_ai],"-h")){
            printf("lemoncore v%s\n用法:\n  lemoncore [PORT] [UP_PORT] [UP_ENABLED]\n  lemoncore --version | --help\n",CORE_VER);
            return 0;
        }
    }
    if(argc>1){ PORT = atoi(argv[1]);
        if(PORT<=0||PORT>65535){ fprintf(stderr,"[lemoncore] 非法端口: %s (合法范围 1-65535)\n",argv[1]); return 1; } }
    if(argc>2){ UP_PORT = atoi(argv[2]);
        if(UP_PORT<0||UP_PORT>65535){ fprintf(stderr,"[lemoncore] 非法上游端口: %s\n",argv[2]); return 1; } }
    if(argc>3) UP_ENABLED = atoi(argv[3]);
    signal(SIGCHLD, on_sigchld);
    signal(SIGPIPE,SIG_IGN);
    M = (Metrics*)mmap(NULL,sizeof(Metrics),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    if(M==MAP_FAILED){ perror("mmap"); return 1; }
    memset((void*)M,0,sizeof(Metrics));
    M->start_ms = now_ms();
    registry_load();

    int l4 = listen_v4(PORT);
    int l6 = listen_v6(PORT);
    if(l4<0 && l6<0){
        fprintf(stderr,"[lemoncore] bind failed on port %d\n",PORT);
        return 1;
    }
    fprintf(stderr,"[lemoncore] v%s native port=%d v4=%s v6=%s tools=%d upstream=%d:%s\n",
            CORE_VER, PORT, l4>=0?"on":"off", l6>=0?"on":"off",
            TN, UP_PORT, UP_ENABLED?"on":"off");

    long kids = 0;
    for(;;){
        while(waitpid(-1,NULL,WNOHANG) > 0) kids--;
        FILE *rf = fopen(RELOAD_FLAG,"r");
        if(rf){ fclose(rf); unlink(RELOAD_FLAG); reg_clear(); registry_load(); }

        struct pollfd lp[2];
        int n = 0;
        if(l4>=0){ lp[n].fd=l4; lp[n].events=POLLIN; lp[n].revents=0; n++; }
        if(l6>=0){ lp[n].fd=l6; lp[n].events=POLLIN; lp[n].revents=0; n++; }
        if(n==0) break;
        int pr = poll(lp,(nfds_t)n,1000);
        if(pr<=0) continue;
        int cfd = -1;
        for(int k=0;k<n && cfd<0;k++){
            if(lp[k].revents & POLLIN) cfd = accept(lp[k].fd,NULL,NULL);
        }
        if(cfd<0) continue;
        if(kids >= MAX_CHILDREN){
            respond(cfd,503,"",0);   /* 过载保护：快速失败，绝不挂起客户端 */
            close(cfd);
            continue;
        }

        struct timeval tv; tv.tv_sec=30; tv.tv_usec=0;
        setsockopt(cfd,SOL_SOCKET,SO_RCVTIMEO,&tv,sizeof tv);
        setsockopt(cfd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof tv);
        { int nd=1; setsockopt(cfd,IPPROTO_TCP,TCP_NODELAY,&nd,sizeof nd); }
        fcntl(cfd,F_SETFD,FD_CLOEXEC);

        kids++;
        pid_t pid = fork();
        if(pid==0){
            if(l4>=0) close(l4);
            if(l6>=0) close(l6);
            M->conns++;
            handle_conn(cfd);
            close(cfd);
            _exit(0);
        }
        if(pid<0){ kids--; close(cfd); usleep(10000); continue; }
        close(cfd);
    }
    return 0;
}
