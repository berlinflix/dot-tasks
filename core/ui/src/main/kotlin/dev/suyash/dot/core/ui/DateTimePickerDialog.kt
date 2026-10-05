package dev.suyash.dot.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Two-step custom picker (date, then time) that returns a [ZonedDateTime] in the device zone.
 * Past dates are not selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initial: ZonedDateTime,
    onDismiss: () -> Unit,
    onConfirm: (ZonedDateTime) -> Unit,
) {
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val today = remember { LocalDate.now(initial.zone) }

    if (pickedDate == null) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        pickedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        val context = LocalContext.current
        val timeState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: return@TextButton
                    onConfirm(ZonedDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute), initial.zone))
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("Back") } },
            text = { TimePicker(state = timeState) },
        )
    }
}
