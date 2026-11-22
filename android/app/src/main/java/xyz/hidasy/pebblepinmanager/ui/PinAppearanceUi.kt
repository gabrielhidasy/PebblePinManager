package xyz.hidasy.pebblepinmanager.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon

fun PinIcon.toImageVector(): ImageVector = when (this) {
    PinIcon.TIMELINE_CALENDAR -> Icons.Default.CalendarMonth
    PinIcon.NOTIFICATION_FLAG -> Icons.Default.Flag
    PinIcon.TIMELINE_SPORTS -> Icons.Default.SportsBasketball
    PinIcon.GENERIC_CONFIRMATION -> Icons.Default.CheckCircle
    PinIcon.TIMELINE_WEATHER -> Icons.Default.WbSunny
    PinIcon.AUDIO_CASSETTE -> Icons.Default.Album
    PinIcon.ALARM_CLOCK -> Icons.Default.Alarm
    PinIcon.DINNER_RESERVATION -> Icons.Default.Restaurant
    PinIcon.SCHEDULED_FLIGHT -> Icons.Default.Flight
    PinIcon.CAR_RENTAL -> Icons.Default.DirectionsCar
    PinIcon.NOTIFICATION_REMINDER -> Icons.Default.Notifications
    PinIcon.LOCATION -> Icons.Default.LocationOn
    PinIcon.BIRTHDAY_EVENT -> Icons.Default.Cake
    PinIcon.MOVIE_EVENT -> Icons.Default.Movie
    PinIcon.MUSIC_EVENT -> Icons.Default.MusicNote
    PinIcon.GENERIC_EMAIL -> Icons.Default.Email
}

fun PinAccentColor.toComposeColor(): Color = Color(argb)

fun Color.contrastingCheckColor(): Color = if (luminance() > 0.5f) Color.Black else Color.White

fun parseComposeHexColor(hex: String?): Color? {
    val clean = hex?.trim()?.removePrefix("#") ?: return null
    if (clean.length != 6) return null
    val rgb = clean.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}
