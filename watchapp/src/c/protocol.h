#pragma once

#include <stdint.h>

/**
 * Shared AppMessage protocol between the PebblePinManager watchapp (C) and the
 * PebblePinManager Android companion app (WatchAppProtocol.kt via PebbleKit Android 2).
 */

/* AppMessage dictionary keys */
#define KEY_MSG_TYPE        1
#define KEY_PIN_COUNT       2
#define KEY_PIN_INDEX       3
#define KEY_PIN_ID          4
#define KEY_PIN_TITLE       5
#define KEY_PIN_TIME        6
#define KEY_DELTA_MINUTES   7
#define KEY_DELTA_DAYS      8
#define KEY_STATUS_OK       9
#define KEY_STATUS_TEXT     10
#define KEY_SYNC_GENERATION 11

/* Watch -> Phone message types */
#define MSG_WATCH_REQ_SYNC       1
#define MSG_WATCH_REQ_OPEN_PHONE 2
#define MSG_WATCH_REQ_RESCHEDULE 3
#define MSG_WATCH_REQ_DELETE     4

/* Phone -> Watch message types */
#define MSG_PHONE_SYNC_START     10
#define MSG_PHONE_PIN_ITEM       11
#define MSG_PHONE_ACTION_RESULT  12

/* Fixed buffer & list limits sized to fit comfortably even on aplite (24 KB RAM) */
#define PPM_MAX_PINS             12
#define PPM_PIN_ID_BUF_SIZE      24
#define PPM_PIN_TITLE_BUF_SIZE   40
#define PPM_PIN_TIME_BUF_SIZE    28
#define PPM_STATUS_TEXT_BUF_SIZE 48

#define PPM_INBOX_SIZE           256
#define PPM_OUTBOX_SIZE          128
