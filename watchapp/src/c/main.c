#include <pebble.h>

static Window *s_window;
static StatusBarLayer *s_status_bar;
static Layer *s_header_bg_layer;
static TextLayer *s_title_layer;
static TextLayer *s_status_layer;
static TextLayer *s_hint_layer;

static void prv_update_connection_status(bool connected) {
  if (!s_status_layer) {
    return;
  }
  if (connected) {
    text_layer_set_text(s_status_layer, "Phone: Connected\nReady for pins");
    text_layer_set_text_color(s_status_layer, GColorDarkGreen);
  } else {
    text_layer_set_text(s_status_layer, "Phone: Disconnected\nOpen Pebble app");
    text_layer_set_text_color(s_status_layer, GColorDarkCandyAppleRed);
  }
}

static void prv_bt_handler(bool connected) {
  prv_update_connection_status(connected);
}

static void prv_header_bg_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  graphics_context_set_fill_color(ctx, GColorOrange);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);
}

static void prv_window_load(Window *window) {
  Layer *root_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root_layer);

  window_set_background_color(window, GColorWhite);

  s_status_bar = status_bar_layer_create();
  status_bar_layer_set_colors(s_status_bar, GColorOrange, GColorWhite);
  status_bar_layer_set_separator_mode(s_status_bar, StatusBarLayerSeparatorModeNone);
  layer_add_child(root_layer, status_bar_layer_get_layer(s_status_bar));

  const int16_t status_h = STATUS_BAR_LAYER_HEIGHT;
  const int16_t header_h = PBL_IF_RECT_ELSE(bounds.size.h >= 200 ? 44 : 34, 40);

  s_header_bg_layer = layer_create(GRect(0, status_h, bounds.size.w, header_h));
  layer_set_update_proc(s_header_bg_layer, prv_header_bg_update_proc);
  layer_add_child(root_layer, s_header_bg_layer);

  s_title_layer = text_layer_create(GRect(6, status_h + 4, bounds.size.w - 12, header_h - 6));
  text_layer_set_background_color(s_title_layer, GColorClear);
  text_layer_set_text_color(s_title_layer, GColorWhite);
  text_layer_set_font(
      s_title_layer,
      fonts_get_system_font(bounds.size.w >= 180 ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_title_layer, GTextAlignmentCenter);
  text_layer_set_text(s_title_layer, "PebblePinManager");
  layer_add_child(root_layer, text_layer_get_layer(s_title_layer));

  const int16_t body_y = status_h + header_h + 10;
  const int16_t status_block_h = bounds.size.h >= 200 ? 52 : 42;

  s_status_layer = text_layer_create(GRect(10, body_y, bounds.size.w - 20, status_block_h));
  text_layer_set_background_color(s_status_layer, GColorClear);
  text_layer_set_font(
      s_status_layer,
      fonts_get_system_font(bounds.size.w >= 180 ? FONT_KEY_GOTHIC_24_BOLD : FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  layer_add_child(root_layer, text_layer_get_layer(s_status_layer));

  const int16_t hint_y = body_y + status_block_h + 6;
  const int16_t hint_h = bounds.size.h - hint_y - 8;

  s_hint_layer = text_layer_create(GRect(10, hint_y, bounds.size.w - 20, hint_h > 20 ? hint_h : 20));
  text_layer_set_background_color(s_hint_layer, GColorClear);
  text_layer_set_text_color(s_hint_layer, GColorDarkGray);
  text_layer_set_font(
      s_hint_layer,
      fonts_get_system_font(bounds.size.w >= 180 ? FONT_KEY_GOTHIC_18 : FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_hint_layer, GTextAlignmentCenter);
  text_layer_set_text(
      s_hint_layer,
      "Create pins in the Android app.\nView them on your watch Timeline.");
  layer_add_child(root_layer, text_layer_get_layer(s_hint_layer));

  prv_update_connection_status(connection_service_peek_pebble_app_connection());
}

static void prv_window_unload(Window *window) {
  text_layer_destroy(s_hint_layer);
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_title_layer);
  layer_destroy(s_header_bg_layer);
  status_bar_layer_destroy(s_status_bar);
  s_hint_layer = NULL;
  s_status_layer = NULL;
  s_title_layer = NULL;
  s_header_bg_layer = NULL;
  s_status_bar = NULL;
}

static void prv_init(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);

  connection_service_subscribe((ConnectionHandlers) {
    .pebble_app_connection_handler = prv_bt_handler,
  });
}

static void prv_deinit(void) {
  connection_service_unsubscribe();
  window_destroy(s_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
  return 0;
}
