/*
 * Android-side dnssd.c replacement.
 *
 * UxPlay's lib/dnssd.c registers the _raop._tcp / _airplay._tcp services via
 * Apple Bonjour (DNSServiceRegister). On Android we advertise via NsdManager
 * from Kotlin (see com.tarmac.service.BonjourAdvertiser), so the registration
 * APIs become no-ops here.
 *
 * What we DO still need: raop_handlers.h reads back the device name, hw_addr,
 * features bits, and the raw mDNS TXT record bytes via dnssd_get_*. Those
 * getters need to return sane data because they end up inside the plist
 * responses to `GET /info`. The TXT record bytes are built up in the same
 * length-prefixed format Bonjour uses: <len><key=value><len><key=value>...
 *
 * Kept minimal on purpose — the functional Bonjour responder is on the Java
 * side.
 */

#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"

#define TXT_MAX_BYTES 1024

struct dnssd_s {
    char *name;
    int   name_len;

    char *hw_addr;
    int   hw_addr_len;

    char *pk;

    uint32_t features1;
    uint32_t features2;

    unsigned char pin_pw;

    unsigned char raop_txt[TXT_MAX_BYTES];
    int raop_txt_len;

    unsigned char airplay_txt[TXT_MAX_BYTES];
    int airplay_txt_len;
};

/* Append "<len>key=value" to buf. Returns new length, or -1 on overflow. */
static int txt_append(unsigned char *buf, int len, int cap, const char *entry) {
    size_t n = strlen(entry);
    if (n > 255) return -1;
    if (len + 1 + (int)n > cap) return -1;
    buf[len++] = (unsigned char) n;
    memcpy(buf + len, entry, n);
    return len + (int) n;
}

static int build_raop_txt(dnssd_t *d) {
    unsigned char *b = d->raop_txt;
    int len = 0;
    char hw_hex[3 * 6 + 1] = {0};
    char features[22] = {0};
    char sf[16];
    const char *pw;
    char kv[128];

    if (d->hw_addr_len >= 6) {
        snprintf(hw_hex, sizeof(hw_hex), "%02X%02X%02X%02X%02X%02X",
                 (unsigned char) d->hw_addr[0], (unsigned char) d->hw_addr[1],
                 (unsigned char) d->hw_addr[2], (unsigned char) d->hw_addr[3],
                 (unsigned char) d->hw_addr[4], (unsigned char) d->hw_addr[5]);
    } else {
        snprintf(hw_hex, sizeof(hw_hex), "000000000000");
    }

    snprintf(features, sizeof(features), "0x%X,0x%X", d->features1, d->features2);

    switch (d->pin_pw) {
    case 2:
    case 3:
        pw = "true";  snprintf(sf, sizeof(sf), "0x84"); break;
    case 1:
        pw = "true";  snprintf(sf, sizeof(sf), "0x8c"); break;
    default:
        pw = "false"; snprintf(sf, sizeof(sf), "%s", RAOP_SF); break;
    }

#define APPEND(key, val) \
    do { snprintf(kv, sizeof(kv), "%s=%s", (key), (val)); \
         len = txt_append(b, len, TXT_MAX_BYTES, kv); \
         if (len < 0) return -1; } while (0)

    APPEND("ch",   RAOP_CH);
    APPEND("cn",   RAOP_CN);
    APPEND("da",   RAOP_DA);
    APPEND("et",   RAOP_ET);
    APPEND("vv",   RAOP_VV);
    APPEND("ft",   features);
    APPEND("am",   GLOBAL_MODEL);
    APPEND("md",   RAOP_MD);
    APPEND("rhd",  RAOP_RHD);
    APPEND("pw",   pw);
    APPEND("sf",   sf);
    APPEND("sr",   RAOP_SR);
    APPEND("ss",   RAOP_SS);
    APPEND("sv",   RAOP_SV);
    APPEND("tp",   RAOP_TP);
    APPEND("txtvers", "1");
    APPEND("vs",   GLOBAL_VERSION);
    APPEND("vn",   RAOP_VN);
    {
        char deviceid[64];
        snprintf(deviceid, sizeof(deviceid), "%02X:%02X:%02X:%02X:%02X:%02X",
                 (unsigned char) d->hw_addr[0], (unsigned char) d->hw_addr[1],
                 (unsigned char) d->hw_addr[2], (unsigned char) d->hw_addr[3],
                 (unsigned char) d->hw_addr[4], (unsigned char) d->hw_addr[5]);
        APPEND("deviceid", deviceid);
    }

#undef APPEND
    return len;
}

static int build_airplay_txt(dnssd_t *d) {
    unsigned char *b = d->airplay_txt;
    int len = 0;
    char kv[128];
    char features[22] = {0};
    char deviceid[64];
    const char *pw;

    snprintf(features, sizeof(features), "0x%X,0x%X", d->features1, d->features2);
    snprintf(deviceid, sizeof(deviceid), "%02X:%02X:%02X:%02X:%02X:%02X",
             (unsigned char) d->hw_addr[0], (unsigned char) d->hw_addr[1],
             (unsigned char) d->hw_addr[2], (unsigned char) d->hw_addr[3],
             (unsigned char) d->hw_addr[4], (unsigned char) d->hw_addr[5]);
    pw = (d->pin_pw ? "true" : "false");

#define APPEND(key, val) \
    do { snprintf(kv, sizeof(kv), "%s=%s", (key), (val)); \
         len = txt_append(b, len, TXT_MAX_BYTES, kv); \
         if (len < 0) return -1; } while (0)

    APPEND("deviceid", deviceid);
    APPEND("features", features);
    APPEND("flags",    AIRPLAY_FLAGS);
    APPEND("model",    GLOBAL_MODEL);
    APPEND("pi",       AIRPLAY_PI);
    APPEND("srcvers",  GLOBAL_VERSION);
    APPEND("vv",       AIRPLAY_VV);
    APPEND("pw",       pw);

#undef APPEND
    return len;
}

dnssd_t *dnssd_init(const char *name, int name_len,
                    const char *hw_addr, int hw_addr_len,
                    int *error, unsigned char pin_pw) {
    if (error) *error = DNSSD_ERROR_NOERROR;
    if (hw_addr_len != 6) {
        if (error) *error = DNSSD_ERROR_HWADDRLEN;
        return NULL;
    }
    dnssd_t *d = (dnssd_t *) calloc(1, sizeof(dnssd_t));
    if (!d) { if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }

    d->pin_pw = pin_pw;

    char *end = NULL;
    unsigned long f = strtoul(FEATURES_1, &end, 16);
    d->features1 = (uint32_t) f;
    f = strtoul(FEATURES_2, &end, 16);
    d->features2 = (uint32_t) f;

    d->name = (char *) calloc(1, name_len + 1);
    if (!d->name) { free(d); if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    memcpy(d->name, name, name_len);
    d->name_len = name_len;

    d->hw_addr = (char *) calloc(1, hw_addr_len);
    if (!d->hw_addr) { free(d->name); free(d); if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    memcpy(d->hw_addr, hw_addr, hw_addr_len);
    d->hw_addr_len = hw_addr_len;

    d->raop_txt_len    = build_raop_txt(d);
    d->airplay_txt_len = build_airplay_txt(d);
    if (d->raop_txt_len < 0 || d->airplay_txt_len < 0) {
        free(d->hw_addr); free(d->name); free(d);
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    return d;
}

void dnssd_destroy(dnssd_t *d) {
    if (!d) return;
    free(d->name);
    free(d->hw_addr);
    free(d);
}

int dnssd_register_raop(dnssd_t *d, unsigned short port) {
    (void) d; (void) port;
    /* NsdManager handles real registration on the Java side. */
    return 0;
}

int dnssd_register_airplay(dnssd_t *d, unsigned short port) {
    (void) d; (void) port;
    return 0;
}

void dnssd_unregister_raop(dnssd_t *d)    { (void) d; }
void dnssd_unregister_airplay(dnssd_t *d) { (void) d; }

const char *dnssd_get_raop_txt(dnssd_t *d, int *length) {
    *length = d->raop_txt_len;
    return (const char *) d->raop_txt;
}

const char *dnssd_get_airplay_txt(dnssd_t *d, int *length) {
    *length = d->airplay_txt_len;
    return (const char *) d->airplay_txt;
}

const char *dnssd_get_name(dnssd_t *d, int *length) {
    *length = d->name_len;
    return d->name;
}

const char *dnssd_get_hw_addr(dnssd_t *d, int *length) {
    *length = d->hw_addr_len;
    return d->hw_addr;
}

uint64_t dnssd_get_airplay_features(dnssd_t *d) {
    return ((uint64_t) d->features2) << 32 | (uint64_t) d->features1;
}

void dnssd_set_airplay_features(dnssd_t *d, int bit, int val) {
    if (bit < 0 || bit > 63) return;
    if (val < 0 || val > 1) return;
    uint32_t *f = (bit >= 32) ? &d->features2 : &d->features1;
    uint32_t mask = 0x1u << (bit & 31);
    if (val) *f |= mask; else *f &= ~mask;
    /* rebuild TXT so subsequent GET /info reflects the change */
    d->raop_txt_len    = build_raop_txt(d);
    d->airplay_txt_len = build_airplay_txt(d);
}

void dnssd_set_pk(dnssd_t *d, char *pk_str) {
    d->pk = pk_str;
}
