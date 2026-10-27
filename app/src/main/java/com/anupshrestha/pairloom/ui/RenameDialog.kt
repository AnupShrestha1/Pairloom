package com.anupshrestha.pairloom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.anupshrestha.pairloom.R
import com.anupshrestha.pairloom.session.NameStore

/** Asks for a name; [onDone] gets the new name, or null if cancelled. */
@Composable
fun RenameDialog(title: String, initial: String, hint: String? = null, onDone: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column {
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(NameStore.MAX_CHARS) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
