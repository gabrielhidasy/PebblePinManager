#include <pebble.h>
#include <stdio.h>
#include <string.h>

#include "protocol.h"

typedef struct {
  char id[PPM_PIN_ID_BUF_SIZE];
  char title[PPM_PIN_TITLE_BUF_SIZE];
  char time_label[PPM_PIN_TIME_BUF_SIZE];
} PinSummary;

typedef struct {
  const char *label;
  int32_t delta_minutes;
  int32_t delta_days;
} RescheduleOption;

typedef struct {
  char pin_id[PPM_PIN_ID_BUF_SIZE];
} PinActionContext;

typedef enum {
  BANNER_INFO = 0,
  BANNER_SUCCESS = 1,
  BANNER_ERROR = 2,
} BannerKind;

static const RescheduleOption s_reschedule_options[] = {
  { "+15 min",        15,  0 },
  { "+30 min",        30,  0 },
  { "+1 hour",        60,  0 },
  { "+3 hours",      180,  0 },
  { "Tomorrow (+1d)",  0,  1 },
  { "-15 min",       -15,  0 },
  { "-1 hour",       -60,  0 },
};

static const uint16_t s_reschedule_option_count =
    (uint16_t)(sizeof(s_reschedule_options) / sizeof(s_reschedule_options[0]));

/* Active pin list rendered in MenuLayer */
static PinSummary s_pins[PPM_MAX_PINS];
static uint8_t s_pin_count = 0;

/* Staging buffer for incoming phone sync batches */
static PinSummary s_staging_pins[PPM_MAX_PINS];
static uint16_t s_staging_received_mask = 0;
static uint8_t s_expected_count = 0;
static uint16_t s_sync_generation = 0;
static bool s_syncing = false;
static bool s_has_synced = false;
static bool s_phone_connected = false;

/* UI handles */
static Window *s_window = NULL;
static StatusBarLayer *s_status_bar = NULL;
static MenuLayer *s_menu_layer = NULL;
static TextLayer *s_banner_layer = NULL;
static AppTimer *s_banner_timer = NULL;
static AppTimer *s_init_sync_timer = NULL;

/* Active ActionMenu handles */
static ActionMenu *s_action_menu = NULL;
static ActionMenuLevel *s_action_root_level = NULL;
static PinActionContext s_action_ctx;

/* Static text buffers */
#if !defined(PBL_ROUND)
static char s_header_buf[24];
#endif
static char s_banner_buf[PPM_STATUS_TEXT_BUF_SIZE];

static void prv_copy_str(char *dst, size_t dst_size, const char *src) {
  if (!dst || dst_size == 0) {
    return;
  }
  if (!src) {
    dst[0] = '\0';
    return;
  }
  strncpy(dst, src, dst_size - 1);
  dst[dst_size - 1] = '\0';
}

static int32_t prv_tuple_to_int32(const Tuple *t) {
  if (!t) {
    return 0;
  }
  if (t->type == TUPLE_INT) {
    switch (t->length) {
      case 1: return t->value->int8;
      case 2: return t->value->int16;
      case 4: return t->value->int32;
      default: return t->value->int32;
    }
  }
  if (t->type == TUPLE_UINT) {
    switch (t->length) {
      case 1: return (int32_t)t->value->uint8;
      case 2: return (int32_t)t->value->uint16;
      case 4: return (int32_t)t->value->uint32;
      default: return (int32_t)t->value->uint32;
    }
  }
  return 0;
}

static void prv_hide_banner_cb(void *context) {
  (void)context;
  s_banner_timer = NULL;
  if (s_banner_layer) {
    layer_set_hidden(text_layer_get_layer(s_banner_layer), true);
  }
}

static void prv_show_banner(const char *message, BannerKind kind, uint32_t duration_ms) {
  if (!s_banner_layer || !message) {
    return;
  }
  if (s_banner_timer) {
    app_timer_cancel(s_banner_timer);
    s_banner_timer = NULL;
  }
  prv_copy_str(s_banner_buf, sizeof(s_banner_buf), message);

  GColor bg = PBL_IF_COLOR_ELSE(GColorCobaltBlue, GColorBlack);
  if (kind == BANNER_SUCCESS) {
    bg = PBL_IF_COLOR_ELSE(GColorDarkGreen, GColorBlack);
  } else if (kind == BANNER_ERROR) {
    bg = PBL_IF_COLOR_ELSE(GColorDarkCandyAppleRed, GColorBlack);
  }

  text_layer_set_background_color(s_banner_layer, bg);
  text_layer_set_text_color(s_banner_layer, GColorWhite);
  text_layer_set_text(s_banner_layer, s_banner_buf);
  layer_set_hidden(text_layer_get_layer(s_banner_layer), false);

  if (duration_ms > 0) {
    s_banner_timer = app_timer_register(duration_ms, prv_hide_banner_cb, NULL);
  }
}

static void prv_reload_menu(void) {
  if (s_menu_layer) {
    menu_layer_reload_data(s_menu_layer);
  }
}

static bool prv_send_simple_request(uint8_t msg_type) {
  if (!s_phone_connected) {
    prv_show_banner("Phone disconnected", BANNER_ERROR, 2500);
    vibes_double_pulse();
    return false;
  }

  DictionaryIterator *iter = NULL;
  AppMessageResult res = app_message_outbox_begin(&iter);
  if (res != APP_MSG_OK || !iter) {
    prv_show_banner("Watch busy, retry", BANNER_ERROR, 2000);
    return false;
  }

  dict_write_uint8(iter, KEY_MSG_TYPE, msg_type);
  res = app_message_outbox_send();
  if (res != APP_MSG_OK) {
    prv_show_banner("Send failed", BANNER_ERROR, 2000);
    return false;
  }
  return true;
}

static void prv_request_sync(bool show_feedback) {
  if (!s_phone_connected) {
    s_syncing = false;
    prv_reload_menu();
    if (show_feedback) {
      prv_show_banner("Phone disconnected", BANNER_ERROR, 2500);
    }
    return;
  }

  if (prv_send_simple_request(MSG_WATCH_REQ_SYNC)) {
    s_syncing = true;
    prv_reload_menu();
    if (show_feedback) {
      prv_show_banner("Syncing pins...", BANNER_INFO, 2000);
    }
  }
}

static void prv_request_open_phone(void) {
  if (prv_send_simple_request(MSG_WATCH_REQ_OPEN_PHONE)) {
    prv_show_banner("Opening phone app...", BANNER_INFO, 2500);
  }
}

static void prv_request_reschedule(const char *pin_id, int32_t delta_minutes, int32_t delta_days) {
  if (!pin_id || pin_id[0] == '\0') {
    return;
  }
  if (!s_phone_connected) {
    prv_show_banner("Phone disconnected", BANNER_ERROR, 2500);
    vibes_double_pulse();
    return;
  }

  DictionaryIterator *iter = NULL;
  AppMessageResult res = app_message_outbox_begin(&iter);
  if (res != APP_MSG_OK || !iter) {
    prv_show_banner("Watch busy, retry", BANNER_ERROR, 2000);
    return;
  }

  dict_write_uint8(iter, KEY_MSG_TYPE, MSG_WATCH_REQ_RESCHEDULE);
  dict_write_cstring(iter, KEY_PIN_ID, pin_id);
  dict_write_int32(iter, KEY_DELTA_MINUTES, delta_minutes);
  dict_write_int32(iter, KEY_DELTA_DAYS, delta_days);

  res = app_message_outbox_send();
  if (res == APP_MSG_OK) {
    prv_show_banner("Rescheduling pin...", BANNER_INFO, 3000);
  } else {
    prv_show_banner("Reschedule failed", BANNER_ERROR, 2500);
  }
}

static void prv_request_delete(const char *pin_id) {
  if (!pin_id || pin_id[0] == '\0') {
    return;
  }
  if (!s_phone_connected) {
    prv_show_banner("Phone disconnected", BANNER_ERROR, 2500);
    vibes_double_pulse();
    return;
  }

  DictionaryIterator *iter = NULL;
  AppMessageResult res = app_message_outbox_begin(&iter);
  if (res != APP_MSG_OK || !iter) {
    prv_show_banner("Watch busy, retry", BANNER_ERROR, 2000);
    return;
  }

  dict_write_uint8(iter, KEY_MSG_TYPE, MSG_WATCH_REQ_DELETE);
  dict_write_cstring(iter, KEY_PIN_ID, pin_id);

  res = app_message_outbox_send();
  if (res == APP_MSG_OK) {
    prv_show_banner("Deleting pin...", BANNER_INFO, 3000);
  } else {
    prv_show_banner("Delete failed", BANNER_ERROR, 2500);
  }
}

/* ActionMenu callbacks */
static void prv_action_reschedule_cb(ActionMenu *action_menu, const ActionMenuItem *action,
                                     void *context) {
  (void)action_menu;
  const RescheduleOption *opt = (const RescheduleOption *)action_menu_item_get_action_data(action);
  const PinActionContext *ctx = (const PinActionContext *)context;
  if (opt && ctx) {
    prv_request_reschedule(ctx->pin_id, opt->delta_minutes, opt->delta_days);
  }
}

static void prv_action_delete_confirm_cb(ActionMenu *action_menu, const ActionMenuItem *action,
                                         void *context) {
  (void)action_menu;
  (void)action;
  const PinActionContext *ctx = (const PinActionContext *)context;
  if (ctx) {
    prv_request_delete(ctx->pin_id);
  }
}

static void prv_action_cancel_cb(ActionMenu *action_menu, const ActionMenuItem *action,
                                 void *context) {
  (void)action_menu;
  (void)action;
  (void)context;
}

static void prv_action_open_phone_cb(ActionMenu *action_menu, const ActionMenuItem *action,
                                     void *context) {
  (void)action_menu;
  (void)action;
  (void)context;
  prv_request_open_phone();
}

static void prv_action_menu_did_close(ActionMenu *menu, const ActionMenuItem *performed_action,
                                      void *context) {
  (void)menu;
  (void)performed_action;
  (void)context;
  if (s_action_root_level) {
    action_menu_hierarchy_destroy(s_action_root_level, NULL, NULL);
    s_action_root_level = NULL;
  }
  s_action_menu = NULL;
}

static void prv_open_pin_action_menu(const PinSummary *pin) {
  if (!pin || pin->id[0] == '\0') {
    return;
  }
  if (s_action_root_level) {
    action_menu_hierarchy_destroy(s_action_root_level, NULL, NULL);
    s_action_root_level = NULL;
  }

  prv_copy_str(s_action_ctx.pin_id, sizeof(s_action_ctx.pin_id), pin->id);

  s_action_root_level = action_menu_level_create(3);
  ActionMenuLevel *reschedule_level = action_menu_level_create(s_reschedule_option_count);
  ActionMenuLevel *delete_level = action_menu_level_create(2);

  if (!s_action_root_level || !reschedule_level || !delete_level) {
    if (s_action_root_level) {
      action_menu_hierarchy_destroy(s_action_root_level, NULL, NULL);
      s_action_root_level = NULL;
    }
    if (reschedule_level) {
      action_menu_hierarchy_destroy(reschedule_level, NULL, NULL);
    }
    if (delete_level) {
      action_menu_hierarchy_destroy(delete_level, NULL, NULL);
    }
    return;
  }

  for (uint16_t i = 0; i < s_reschedule_option_count; i++) {
    action_menu_level_add_action(
        reschedule_level,
        s_reschedule_options[i].label,
        prv_action_reschedule_cb,
        (void *)&s_reschedule_options[i]);
  }

  action_menu_level_add_action(delete_level, "Confirm Delete", prv_action_delete_confirm_cb, NULL);
  action_menu_level_add_action(delete_level, "Cancel", prv_action_cancel_cb, NULL);

  action_menu_level_add_child(s_action_root_level, reschedule_level, "Reschedule");
  action_menu_level_add_child(s_action_root_level, delete_level, "Delete Pin");
  action_menu_level_add_action(s_action_root_level, "Open Phone App", prv_action_open_phone_cb, NULL);

  ActionMenuConfig config = (ActionMenuConfig) {
    .root_level = s_action_root_level,
    .context = &s_action_ctx,
    .colors = {
      .background = PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack),
      .foreground = GColorWhite,
    },
    .did_close = prv_action_menu_did_close,
    .align = PBL_IF_ROUND_ELSE(ActionMenuAlignCenter, ActionMenuAlignTop),
  };

  s_action_menu = action_menu_open(&config);
}

/* MenuLayer callbacks */
static uint16_t prv_menu_get_num_sections(MenuLayer *menu_layer, void *data) {
  (void)menu_layer;
  (void)data;
  return s_pin_count > 0 ? 2 : 1;
}

static uint16_t prv_menu_get_num_rows(MenuLayer *menu_layer, uint16_t section_index, void *data) {
  (void)menu_layer;
  (void)data;
  if (s_pin_count == 0) {
    return 3; /* Status/Empty row, Open Phone App, Refresh Pins */
  }
  return section_index == 0 ? s_pin_count : 2;
}

static int16_t prv_menu_get_header_height(MenuLayer *menu_layer, uint16_t section_index,
                                          void *data) {
  (void)menu_layer;
  (void)section_index;
  (void)data;
  return PBL_IF_ROUND_ELSE(0, MENU_CELL_BASIC_HEADER_HEIGHT);
}

static int16_t prv_menu_get_cell_height(MenuLayer *menu_layer, MenuIndex *cell_index, void *data) {
  (void)data;
#if defined(PBL_ROUND)
  return menu_layer_is_index_selected(menu_layer, cell_index)
      ? MENU_CELL_ROUND_FOCUSED_SHORT_CELL_HEIGHT
      : MENU_CELL_ROUND_UNFOCUSED_SHORT_CELL_HEIGHT;
#else
  (void)cell_index;
  GRect bounds = layer_get_bounds(menu_layer_get_layer(menu_layer));
  return bounds.size.h >= 200 ? 48 : 44;
#endif
}

static void prv_menu_draw_header(GContext *ctx, const Layer *cell_layer, uint16_t section_index,
                                 void *data) {
  (void)data;
#if defined(PBL_ROUND)
  (void)ctx;
  (void)cell_layer;
  (void)section_index;
#else
  if (s_pin_count == 0) {
    menu_cell_basic_header_draw(ctx, cell_layer, "PebblePinManager");
    return;
  }
  if (section_index == 0) {
    snprintf(s_header_buf, sizeof(s_header_buf), "Recent Pins (%u)", (unsigned)s_pin_count);
    menu_cell_basic_header_draw(ctx, cell_layer, s_header_buf);
  } else {
    menu_cell_basic_header_draw(ctx, cell_layer, "Actions");
  }
#endif
}

static void prv_menu_draw_row(GContext *ctx, const Layer *cell_layer, MenuIndex *cell_index,
                              void *data) {
  (void)data;
  if (s_pin_count == 0) {
    switch (cell_index->row) {
      case 0:
        if (!s_phone_connected) {
          menu_cell_basic_draw(ctx, cell_layer, "Phone disconnected", "Open Pebble app", NULL);
        } else if (s_syncing) {
          menu_cell_basic_draw(ctx, cell_layer, "Syncing pins...", "Waiting for phone", NULL);
        } else {
          menu_cell_basic_draw(ctx, cell_layer, "No recent pins", "SELECT: Open phone", NULL);
        }
        break;
      case 1:
        menu_cell_basic_draw(ctx, cell_layer, "Open Phone App", "Add a timeline pin", NULL);
        break;
      default:
        menu_cell_basic_draw(ctx, cell_layer, "Refresh Pins",
                             s_phone_connected ? "Sync from phone" : "Reconnect phone", NULL);
        break;
    }
    return;
  }

  if (cell_index->section == 0 && cell_index->row < s_pin_count) {
    const PinSummary *pin = &s_pins[cell_index->row];
    menu_cell_basic_draw(ctx, cell_layer, pin->title, pin->time_label, NULL);
  } else {
    if (cell_index->row == 0) {
      menu_cell_basic_draw(ctx, cell_layer, "Open Phone App", "Hold SELECT anytime", NULL);
    } else {
      menu_cell_basic_draw(ctx, cell_layer, "Refresh Pins",
                           s_phone_connected ? "Sync from phone" : "Phone disconnected", NULL);
    }
  }
}

static void prv_menu_select_click(MenuLayer *menu_layer, MenuIndex *cell_index, void *data) {
  (void)menu_layer;
  (void)data;
  if (s_pin_count == 0) {
    if (cell_index->row == 2) {
      prv_request_sync(true);
    } else {
      prv_request_open_phone();
    }
    return;
  }

  if (cell_index->section == 0 && cell_index->row < s_pin_count) {
    prv_open_pin_action_menu(&s_pins[cell_index->row]);
  } else if (cell_index->row == 0) {
    prv_request_open_phone();
  } else {
    prv_request_sync(true);
  }
}

static void prv_menu_select_long_click(MenuLayer *menu_layer, MenuIndex *cell_index, void *data) {
  (void)menu_layer;
  (void)cell_index;
  (void)data;
  prv_request_open_phone();
}

/* AppMessage callbacks */
static void prv_commit_staging_pins(uint8_t count) {
  if (count > PPM_MAX_PINS) {
    count = PPM_MAX_PINS;
  }
  for (uint8_t i = 0; i < count; i++) {
    s_pins[i] = s_staging_pins[i];
  }
  s_pin_count = count;
  s_has_synced = true;
  prv_reload_menu();
}

static void prv_inbox_received_cb(DictionaryIterator *iter, void *context) {
  (void)context;
  Tuple *type_tuple = dict_find(iter, KEY_MSG_TYPE);
  if (!type_tuple) {
    return;
  }

  int32_t msg_type = prv_tuple_to_int32(type_tuple);

  if (msg_type == MSG_PHONE_SYNC_START) {
    Tuple *count_t = dict_find(iter, KEY_PIN_COUNT);
    Tuple *gen_t = dict_find(iter, KEY_SYNC_GENERATION);
    int32_t raw_count = prv_tuple_to_int32(count_t);
    if (raw_count < 0) {
      raw_count = 0;
    } else if (raw_count > PPM_MAX_PINS) {
      raw_count = PPM_MAX_PINS;
    }

    s_expected_count = (uint8_t)raw_count;
    s_sync_generation = (uint16_t)prv_tuple_to_int32(gen_t);
    s_staging_received_mask = 0;
    memset(s_staging_pins, 0, sizeof(s_staging_pins));

    if (s_expected_count == 0) {
      s_syncing = false;
      s_pin_count = 0;
      s_has_synced = true;
      prv_reload_menu();
    } else {
      s_syncing = true;
    }
    return;
  }

  if (msg_type == MSG_PHONE_PIN_ITEM) {
    Tuple *gen_t = dict_find(iter, KEY_SYNC_GENERATION);
    Tuple *idx_t = dict_find(iter, KEY_PIN_INDEX);
    Tuple *cnt_t = dict_find(iter, KEY_PIN_COUNT);
    Tuple *id_tuple = dict_find(iter, KEY_PIN_ID);
    Tuple *title_tuple = dict_find(iter, KEY_PIN_TITLE);
    Tuple *time_tuple = dict_find(iter, KEY_PIN_TIME);

    if (!idx_t || !title_tuple) {
      return;
    }

    uint16_t gen = (uint16_t)prv_tuple_to_int32(gen_t);
    if (s_sync_generation != 0 && gen != 0 && gen != s_sync_generation) {
      return;
    }

    int32_t idx = prv_tuple_to_int32(idx_t);
    if (idx < 0 || idx >= PPM_MAX_PINS) {
      return;
    }

    if (cnt_t) {
      int32_t cnt = prv_tuple_to_int32(cnt_t);
      if (cnt > 0 && cnt <= PPM_MAX_PINS) {
        s_expected_count = (uint8_t)cnt;
      }
    }

    PinSummary *slot = &s_staging_pins[idx];
    prv_copy_str(slot->id, sizeof(slot->id), id_tuple ? id_tuple->value->cstring : "");
    prv_copy_str(slot->title, sizeof(slot->title), title_tuple->value->cstring);
    prv_copy_str(slot->time_label, sizeof(slot->time_label),
                 time_tuple ? time_tuple->value->cstring : "");

    s_staging_received_mask |= (uint16_t)(1u << (uint8_t)idx);

    uint16_t full_mask = (s_expected_count >= 16)
        ? 0xFFFFu
        : (uint16_t)((1u << s_expected_count) - 1u);

    if (s_expected_count > 0 && (s_staging_received_mask & full_mask) == full_mask) {
      s_syncing = false;
      prv_commit_staging_pins(s_expected_count);
    } else if (!s_has_synced && idx + 1 > s_pin_count) {
      /* Show progressive items on initial empty load for snappy feel */
      s_pins[idx] = *slot;
      s_pin_count = (uint8_t)(idx + 1);
      prv_reload_menu();
    }
    return;
  }

  if (msg_type == MSG_PHONE_ACTION_RESULT) {
    Tuple *ok_t = dict_find(iter, KEY_STATUS_OK);
    Tuple *text_t = dict_find(iter, KEY_STATUS_TEXT);
    bool ok = ok_t ? (prv_tuple_to_int32(ok_t) != 0) : true;
    const char *raw_text = text_t ? text_t->value->cstring : NULL;
    const char *text = (raw_text && raw_text[0] != '\0')
        ? raw_text
        : (ok ? "Done" : "Action failed");

    prv_show_banner(text, ok ? BANNER_SUCCESS : BANNER_ERROR, 2600);
    if (ok) {
      vibes_short_pulse();
    } else {
      vibes_double_pulse();
    }
  }
}

static void prv_outbox_failed_cb(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  (void)iter;
  (void)context;
  s_syncing = false;
  prv_reload_menu();
  if (reason == APP_MSG_NOT_CONNECTED) {
    prv_show_banner("Phone disconnected", BANNER_ERROR, 2500);
  } else if (reason == APP_MSG_SEND_TIMEOUT) {
    prv_show_banner("Phone timed out", BANNER_ERROR, 2500);
  } else {
    prv_show_banner("Phone app not ready", BANNER_ERROR, 2500);
  }
}

static void prv_bt_handler(bool connected) {
  bool was_connected = s_phone_connected;
  s_phone_connected = connected;
  if (!connected) {
    s_syncing = false;
  }
  prv_reload_menu();
  if (connected && !was_connected) {
    prv_request_sync(false);
  }
}

static void prv_init_sync_cb(void *context) {
  (void)context;
  s_init_sync_timer = NULL;
  if (s_phone_connected && !s_has_synced) {
    prv_request_sync(false);
  }
}

static void prv_window_load(Window *window) {
  Layer *root_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root_layer);

  window_set_background_color(window, GColorWhite);

  s_status_bar = status_bar_layer_create();
  status_bar_layer_set_colors(
      s_status_bar,
      PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack),
      GColorWhite);
  status_bar_layer_set_separator_mode(s_status_bar, StatusBarLayerSeparatorModeNone);
  layer_add_child(root_layer, status_bar_layer_get_layer(s_status_bar));

  const int16_t status_h = STATUS_BAR_LAYER_HEIGHT;
  GRect menu_frame = GRect(0, status_h, bounds.size.w, bounds.size.h - status_h);

  s_menu_layer = menu_layer_create(menu_frame);
  menu_layer_set_callbacks(s_menu_layer, NULL, (MenuLayerCallbacks) {
    .get_num_sections = prv_menu_get_num_sections,
    .get_num_rows = prv_menu_get_num_rows,
    .get_header_height = prv_menu_get_header_height,
    .get_cell_height = prv_menu_get_cell_height,
    .draw_header = prv_menu_draw_header,
    .draw_row = prv_menu_draw_row,
    .select_click = prv_menu_select_click,
    .select_long_click = prv_menu_select_long_click,
  });
  menu_layer_set_normal_colors(s_menu_layer, GColorWhite, GColorBlack);
  menu_layer_set_highlight_colors(
      s_menu_layer,
      PBL_IF_COLOR_ELSE(GColorOrange, GColorBlack),
      GColorWhite);
#if defined(PBL_ROUND)
  menu_layer_set_center_focused(s_menu_layer, true);
#endif
  menu_layer_set_click_config_onto_window(s_menu_layer, window);
  layer_add_child(root_layer, menu_layer_get_layer(s_menu_layer));

  /* Transient status toast banner overlay */
  const int16_t banner_inset = PBL_IF_ROUND_ELSE(18, 0);
  const int16_t banner_y = status_h + PBL_IF_ROUND_ELSE(2, 0);
  const int16_t banner_h = 20;
  s_banner_layer = text_layer_create(
      GRect(banner_inset, banner_y, bounds.size.w - (banner_inset * 2), banner_h));
  text_layer_set_font(s_banner_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD));
  text_layer_set_text_alignment(s_banner_layer, GTextAlignmentCenter);
  text_layer_set_overflow_mode(s_banner_layer, GTextOverflowModeTrailingEllipsis);
  layer_set_hidden(text_layer_get_layer(s_banner_layer), true);
  layer_add_child(root_layer, text_layer_get_layer(s_banner_layer));

  s_phone_connected = connection_service_peek_pebble_app_connection();
  prv_reload_menu();
}

static void prv_window_unload(Window *window) {
  (void)window;
  if (s_banner_timer) {
    app_timer_cancel(s_banner_timer);
    s_banner_timer = NULL;
  }
  if (s_init_sync_timer) {
    app_timer_cancel(s_init_sync_timer);
    s_init_sync_timer = NULL;
  }
  if (s_action_root_level) {
    action_menu_hierarchy_destroy(s_action_root_level, NULL, NULL);
    s_action_root_level = NULL;
  }
  s_action_menu = NULL;

  text_layer_destroy(s_banner_layer);
  menu_layer_destroy(s_menu_layer);
  status_bar_layer_destroy(s_status_bar);
  s_banner_layer = NULL;
  s_menu_layer = NULL;
  s_status_bar = NULL;
}

static void prv_init(void) {
  app_message_register_inbox_received(prv_inbox_received_cb);
  app_message_register_outbox_failed(prv_outbox_failed_cb);
  app_message_open(PPM_INBOX_SIZE, PPM_OUTBOX_SIZE);

  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);

  connection_service_subscribe((ConnectionHandlers) {
    .pebble_app_connection_handler = prv_bt_handler,
  });

  s_init_sync_timer = app_timer_register(150, prv_init_sync_cb, NULL);
}

static void prv_deinit(void) {
  connection_service_unsubscribe();
  app_message_deregister_callbacks();
  window_destroy(s_window);
  s_window = NULL;
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
  return 0;
}
