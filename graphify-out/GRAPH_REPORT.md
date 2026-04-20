# Graph Report - /Users/shivang/code/tarmac  (2026-04-20)

## Corpus Check
- 121 files · ~301,316 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1261 nodes · 2923 edges · 82 communities detected
- Extraction: 64% EXTRACTED · 36% INFERRED · 0% AMBIGUOUS · INFERRED: 1043 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 32|Community 32]]
- [[_COMMUNITY_Community 33|Community 33]]
- [[_COMMUNITY_Community 34|Community 34]]
- [[_COMMUNITY_Community 35|Community 35]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 62|Community 62]]
- [[_COMMUNITY_Community 63|Community 63]]
- [[_COMMUNITY_Community 64|Community 64]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 66|Community 66]]
- [[_COMMUNITY_Community 67|Community 67]]
- [[_COMMUNITY_Community 68|Community 68]]
- [[_COMMUNITY_Community 69|Community 69]]
- [[_COMMUNITY_Community 70|Community 70]]
- [[_COMMUNITY_Community 71|Community 71]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]
- [[_COMMUNITY_Community 76|Community 76]]
- [[_COMMUNITY_Community 77|Community 77]]
- [[_COMMUNITY_Community 78|Community 78]]
- [[_COMMUNITY_Community 79|Community 79]]
- [[_COMMUNITY_Community 80|Community 80]]
- [[_COMMUNITY_Community 81|Community 81]]

## God Nodes (most connected - your core abstractions)
1. `llhttp__internal__run()` - 90 edges
2. `logger_log()` - 77 edges
3. `raop_handler_setup()` - 54 edges
4. `http_handler_action()` - 39 edges
5. `plist_get_data()` - 38 edges
6. `main()` - 37 edges
7. `conn_request()` - 35 edges
8. `http_handler_play()` - 34 edges
9. `node_first_child()` - 31 edges
10. `plist_dict_set_item()` - 30 edges

## Surprising Connections (you probably didn't know these)
- `Java_com_tarmac_service_AirPlayJni_startServer()` --calls--> `raop_init2()`  [INFERRED]
  /Users/shivang/code/tarmac/app/src/main/cpp/jni_bridge.cpp → native/libairplay/lib/raop.c
- `raop_rtp_mirror_thread()` --calls--> `plist_from_bin()`  [INFERRED]
  native/libairplay/lib/raop_rtp_mirror.c → /Users/shivang/code/tarmac/native/libplist/src/bplist.c
- `http_handler_action()` --calls--> `plist_from_bin()`  [INFERRED]
  native/libairplay/lib/http_handlers.h → /Users/shivang/code/tarmac/native/libplist/src/bplist.c
- `http_handler_play()` --calls--> `plist_from_bin()`  [INFERRED]
  native/libairplay/lib/http_handlers.h → /Users/shivang/code/tarmac/native/libplist/src/bplist.c
- `raop_handler_info()` --calls--> `plist_to_bin()`  [INFERRED]
  native/libairplay/lib/raop_handlers.h → /Users/shivang/code/tarmac/native/libplist/src/bplist.c

## Hyperedges (group relationships)
- **airplay library links pthread, playfair, llhttp together** — cmakelists_airplay, cmakelists_airplay_pthread, cmakelists_playfair, cmakelists_llhttp [EXTRACTED 1.00]
- **Tarmac A/V pipeline: airplay protocol -> MediaCodec video + AudioTrack audio** — cmakelists_airplay, readme_mediacodec, readme_audiotrack, readme_tarmacservice [INFERRED 0.85]
- **UxPlay README in three formats (html/md/txt)** — readme_libairplay_html, readme_libairplay_md, readme_libairplay_txt [EXTRACTED 1.00]

## Communities

### Community 0 - "Community 0"
Cohesion: 0.02
Nodes (165): audio_renderer_destroy(), audio_renderer_flush(), audio_renderer_init(), audio_renderer_listen(), audio_renderer_render_buffer(), audio_renderer_set_volume(), audio_renderer_start(), audio_renderer_stop() (+157 more)

### Community 1 - "Community 1"
Cohesion: 0.04
Nodes (128): llhttp_errno_name(), llhttp_get_errno(), llhttp_get_error_reason(), plist_from_bin(), dnssd_get_hw_addr(), create_fcup_request(), fcup_request(), create_playback_info_plist_xml() (+120 more)

### Community 2 - "Community 2"
Cohesion: 0.03
Nodes (104): llhttp_alloc(), llhttp_execute(), llhttp_init(), llhttp_method_name(), llhttp__on_body(), llhttp__on_chunk_complete(), llhttp__on_chunk_extension_name(), llhttp__on_chunk_extension_name_complete() (+96 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (82): node_prev_sibling(), memmem(), plist_access_path(), plist_access_pathv(), plist_array_get_item(), plist_array_get_item_index(), plist_array_item_remove(), plist_array_remove_item() (+74 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (80): base64encode(), serialize_plist(), write_array(), write_dict(), hash_table_destroy(), hash_table_insert(), hash_table_lookup(), hash_table_new() (+72 more)

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (67): aes_cbc_decrypt(), aes_cbc_destroy(), aes_cbc_encrypt(), aes_cbc_init(), aes_cbc_reset(), aes_ctr_decrypt(), aes_ctr_destroy(), aes_ctr_encrypt() (+59 more)

### Community 6 - "Community 6"
Cohesion: 0.05
Nodes (63): base64decode(), plist_bin_deinit(), plist_bin_init(), plist_bin_set_debug(), num_digits_i(), num_digits_u(), parse_array(), parse_decimal() (+55 more)

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (26): AudioPipeline, MediaCodecInfoCompat, AirPlay_Service_Discovery_Advertisement, AirPlayAdvertisement, beacon_off(), beacon_on(), check_adv_intrvl(), check_file_exists() (+18 more)

### Community 8 - "Community 8"
Cohesion: 0.1
Nodes (42): get_unaligned_16(), get_unaligned_32(), get_unaligned_64(), is_ascii_string(), parse_array_node(), parse_bin_node(), parse_bin_node_at_index(), parse_data_node() (+34 more)

### Community 9 - "Community 9"
Cohesion: 0.12
Nodes (39): get_random_bytes(), srp_new_user(), srp_validate_proof(), calculate_H_AMK(), calculate_M(), calculate_x(), delete_ng(), H_nn_orig() (+31 more)

### Community 10 - "Community 10"
Cohesion: 0.09
Nodes (39): fairplay_decrypt(), fairplay_destroy(), fairplay_handshake(), fairplay_init(), fairplay_setup(), garble(), weird_rol32(), weird_rol8() (+31 more)

### Community 11 - "Community 11"
Cohesion: 0.09
Nodes (42): adjust_master_playlist(), adjust_yt_condensed_playlist(), airplay_video_destroy(), airplay_video_init(), analyze_media_playlist(), create_media_data_store(), create_media_uri_table(), destroy_media_data_store() (+34 more)

### Community 12 - "Community 12"
Cohesion: 0.1
Nodes (34): byteutils_get_float(), byteutils_get_int(), byteutils_get_int_be(), byteutils_get_long(), byteutils_get_long_be(), byteutils_get_ntp_timestamp(), byteutils_get_short(), byteutils_get_short_be() (+26 more)

### Community 13 - "Community 13"
Cohesion: 0.2
Nodes (17): asctime64_r(), check_tm(), cmp_date(), copy_tm_to_TM64(), ctime64_r(), cycle_offset(), date_in_safe_range(), gmtime64_r() (+9 more)

### Community 14 - "Community 14"
Cohesion: 0.15
Nodes (4): build_airplay_txt(), build_raop_txt(), dnssd_init(), dnssd_set_airplay_features()

### Community 15 - "Community 15"
Cohesion: 0.13
Nodes (1): TarmacService

### Community 16 - "Community 16"
Cohesion: 0.14
Nodes (3): AirPlayJni, Listener, SessionState

### Community 17 - "Community 17"
Cohesion: 0.24
Nodes (9): attach_env(), cb_audio_process(), cb_conn_destroy(), cb_conn_init(), cb_conn_reset(), cb_display_pin(), cb_video_process(), notify_session_state() (+1 more)

### Community 18 - "Community 18"
Cohesion: 0.2
Nodes (11): GNU GPL v3 License (playfair LICENSE.md), AirScreen (comparison baseline), Tarmac Architecture Overview, Android AudioTrack (audio output), Bonjour mDNS advertising, Rationale: GPL-3.0-only because UxPlay subtree is GPL-3.0, Android MediaCodec (tunneled video), Tarmac AirPlay receiver for Android TV (+3 more)

### Community 19 - "Community 19"
Cohesion: 0.2
Nodes (3): Connection, SessionStateBus, Snapshot

### Community 20 - "Community 20"
Cohesion: 0.25
Nodes (1): Prefs

### Community 21 - "Community 21"
Cohesion: 0.29
Nodes (1): MainFragment

### Community 22 - "Community 22"
Cohesion: 0.29
Nodes (2): SettingsRootFragment, TarmacPreferenceFragment

### Community 23 - "Community 23"
Cohesion: 0.29
Nodes (1): BonjourAdvertiser

### Community 24 - "Community 24"
Cohesion: 0.29
Nodes (1): VideoPipeline

### Community 25 - "Community 25"
Cohesion: 0.33
Nodes (1): MirrorActivity

### Community 26 - "Community 26"
Cohesion: 0.4
Nodes (1): StatusItemPresenter

### Community 27 - "Community 27"
Cohesion: 0.5
Nodes (1): MainActivity

### Community 28 - "Community 28"
Cohesion: 0.67
Nodes (1): TarmacApplication

### Community 29 - "Community 29"
Cohesion: 0.67
Nodes (1): SettingsActivity

### Community 30 - "Community 30"
Cohesion: 0.67
Nodes (0): 

### Community 31 - "Community 31"
Cohesion: 0.67
Nodes (3): UxPlay README (HTML variant), UxPlay README (Markdown variant), UxPlay README (text variant)

### Community 32 - "Community 32"
Cohesion: 1.0
Nodes (0): 

### Community 33 - "Community 33"
Cohesion: 1.0
Nodes (0): 

### Community 34 - "Community 34"
Cohesion: 1.0
Nodes (0): 

### Community 35 - "Community 35"
Cohesion: 1.0
Nodes (0): 

### Community 36 - "Community 36"
Cohesion: 1.0
Nodes (0): 

### Community 37 - "Community 37"
Cohesion: 1.0
Nodes (0): 

### Community 38 - "Community 38"
Cohesion: 1.0
Nodes (0): 

### Community 39 - "Community 39"
Cohesion: 1.0
Nodes (0): 

### Community 40 - "Community 40"
Cohesion: 1.0
Nodes (0): 

### Community 41 - "Community 41"
Cohesion: 1.0
Nodes (0): 

### Community 42 - "Community 42"
Cohesion: 1.0
Nodes (0): 

### Community 43 - "Community 43"
Cohesion: 1.0
Nodes (0): 

### Community 44 - "Community 44"
Cohesion: 1.0
Nodes (0): 

### Community 45 - "Community 45"
Cohesion: 1.0
Nodes (0): 

### Community 46 - "Community 46"
Cohesion: 1.0
Nodes (0): 

### Community 47 - "Community 47"
Cohesion: 1.0
Nodes (0): 

### Community 48 - "Community 48"
Cohesion: 1.0
Nodes (0): 

### Community 49 - "Community 49"
Cohesion: 1.0
Nodes (0): 

### Community 50 - "Community 50"
Cohesion: 1.0
Nodes (0): 

### Community 51 - "Community 51"
Cohesion: 1.0
Nodes (0): 

### Community 52 - "Community 52"
Cohesion: 1.0
Nodes (0): 

### Community 53 - "Community 53"
Cohesion: 1.0
Nodes (0): 

### Community 54 - "Community 54"
Cohesion: 1.0
Nodes (0): 

### Community 55 - "Community 55"
Cohesion: 1.0
Nodes (0): 

### Community 56 - "Community 56"
Cohesion: 1.0
Nodes (0): 

### Community 57 - "Community 57"
Cohesion: 1.0
Nodes (0): 

### Community 58 - "Community 58"
Cohesion: 1.0
Nodes (0): 

### Community 59 - "Community 59"
Cohesion: 1.0
Nodes (0): 

### Community 60 - "Community 60"
Cohesion: 1.0
Nodes (0): 

### Community 61 - "Community 61"
Cohesion: 1.0
Nodes (0): 

### Community 62 - "Community 62"
Cohesion: 1.0
Nodes (0): 

### Community 63 - "Community 63"
Cohesion: 1.0
Nodes (0): 

### Community 64 - "Community 64"
Cohesion: 1.0
Nodes (0): 

### Community 65 - "Community 65"
Cohesion: 1.0
Nodes (0): 

### Community 66 - "Community 66"
Cohesion: 1.0
Nodes (0): 

### Community 67 - "Community 67"
Cohesion: 1.0
Nodes (0): 

### Community 68 - "Community 68"
Cohesion: 1.0
Nodes (0): 

### Community 69 - "Community 69"
Cohesion: 1.0
Nodes (0): 

### Community 70 - "Community 70"
Cohesion: 1.0
Nodes (0): 

### Community 71 - "Community 71"
Cohesion: 1.0
Nodes (0): 

### Community 72 - "Community 72"
Cohesion: 1.0
Nodes (0): 

### Community 73 - "Community 73"
Cohesion: 1.0
Nodes (0): 

### Community 74 - "Community 74"
Cohesion: 1.0
Nodes (0): 

### Community 75 - "Community 75"
Cohesion: 1.0
Nodes (0): 

### Community 76 - "Community 76"
Cohesion: 1.0
Nodes (0): 

### Community 77 - "Community 77"
Cohesion: 1.0
Nodes (0): 

### Community 78 - "Community 78"
Cohesion: 1.0
Nodes (0): 

### Community 79 - "Community 79"
Cohesion: 1.0
Nodes (0): 

### Community 80 - "Community 80"
Cohesion: 1.0
Nodes (0): 

### Community 81 - "Community 81"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **15 isolated node(s):** `SessionState`, `Connection`, `Snapshot`, `MediaCodecInfoCompat`, `NotSupportedException` (+10 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 32`** (2 nodes): `wsa_strerror()`, `compat.c`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 33`** (1 nodes): `build.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 34`** (1 nodes): `settings.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 35`** (1 nodes): `build.gradle.kts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 36`** (1 nodes): `android_video_renderer.cpp`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 37`** (1 nodes): `android_audio_renderer.cpp`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 38`** (1 nodes): `config.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 39`** (1 nodes): `node.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 40`** (1 nodes): `object.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 41`** (1 nodes): `node_list.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 42`** (1 nodes): `plist.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 43`** (1 nodes): `strbuf.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 44`** (1 nodes): `bytearray.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 45`** (1 nodes): `base64.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 46`** (1 nodes): `ptrarray.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 47`** (1 nodes): `hashtable.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 48`** (1 nodes): `jsmn.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 49`** (1 nodes): `time64_limits.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 50`** (1 nodes): `time64.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 51`** (1 nodes): `video_renderer.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 52`** (1 nodes): `mux_renderer.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 53`** (1 nodes): `audio_renderer.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 54`** (1 nodes): `utils.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 55`** (1 nodes): `httpd.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 56`** (1 nodes): `netutils.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 57`** (1 nodes): `raop.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 58`** (1 nodes): `airplay_video.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 59`** (1 nodes): `raop_ntp.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 60`** (1 nodes): `global.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 61`** (1 nodes): `threads.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 62`** (1 nodes): `stream.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 63`** (1 nodes): `crypto.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 64`** (1 nodes): `raop_rtp_mirror.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 65`** (1 nodes): `srp.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 66`** (1 nodes): `sockets.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 67`** (1 nodes): `http_response.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 68`** (1 nodes): `dnssdint.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 69`** (1 nodes): `raop_rtp.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 70`** (1 nodes): `pairing.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 71`** (1 nodes): `byteutils.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 72`** (1 nodes): `compat.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 73`** (1 nodes): `raop_buffer.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 74`** (1 nodes): `mirror_buffer.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 75`** (1 nodes): `dnssd.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 76`** (1 nodes): `fairplay.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 77`** (1 nodes): `http_request.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 78`** (1 nodes): `logger.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 79`** (1 nodes): `llhttp.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 80`** (1 nodes): `playfair.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 81`** (1 nodes): `omg_hax.h`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `raop_handler_setup()` connect `Community 1` to `Community 0`, `Community 3`, `Community 4`, `Community 5`, `Community 8`, `Community 10`, `Community 11`, `Community 12`?**
  _High betweenness centrality (0.188) - this node is a cross-community bridge._
- **Why does `logger_log()` connect `Community 1` to `Community 0`, `Community 5`, `Community 10`, `Community 11`, `Community 12`?**
  _High betweenness centrality (0.166) - this node is a cross-community bridge._
- **Why does `httpd_thread()` connect `Community 1` to `Community 2`?**
  _High betweenness centrality (0.119) - this node is a cross-community bridge._
- **Are the 28 inferred relationships involving `llhttp__internal__run()` (e.g. with `llhttp__after_message_complete()` and `llhttp__on_message_complete()`) actually correct?**
  _`llhttp__internal__run()` has 28 INFERRED edges - model-reasoned connections that need verification._
- **Are the 76 inferred relationships involving `logger_log()` (e.g. with `audio_renderer_init()` and `get_renderer_type()`) actually correct?**
  _`logger_log()` has 76 INFERRED edges - model-reasoned connections that need verification._
- **Are the 53 inferred relationships involving `raop_handler_setup()` (e.g. with `logger_get_level()` and `http_request_get_data()`) actually correct?**
  _`raop_handler_setup()` has 53 INFERRED edges - model-reasoned connections that need verification._
- **Are the 36 inferred relationships involving `http_handler_action()` (e.g. with `logger_get_level()` and `http_request_get_header()`) actually correct?**
  _`http_handler_action()` has 36 INFERRED edges - model-reasoned connections that need verification._