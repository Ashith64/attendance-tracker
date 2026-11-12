package app.attendancetracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.attendancetracker.security.AuthSessionSnapshot
import app.attendancetracker.security.LoginErrorKind
import app.attendancetracker.security.SessionState
import app.attendancetracker.security.StudentCredentials
import app.attendancetracker.ui.theme.AppColors
import app.attendancetracker.ui.theme.AttendanceTrackerTheme

@Composable
fun LoginScreen(
    snapshot: AuthSessionSnapshot,
    onSubmit: (StudentCredentials) -> Unit,
    modifier: Modifier = Modifier,
) {
    var usn by rememberSaveable { mutableStateOf("") }
    var dobDay by rememberSaveable { mutableStateOf("") }
    var dobMonth by rememberSaveable { mutableStateOf("") }
    var dobYear by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableIntStateOf(StudentCredentials.RELATION_FATHER) }
    var digits by rememberSaveable { mutableStateOf("") }
    var localValidationError by rememberSaveable { mutableStateOf<String?>(null) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppColors.TextPrimary,
        unfocusedBorderColor = AppColors.BorderSubtle,
        focusedTextColor = AppColors.TextPrimary,
        unfocusedTextColor = AppColors.TextPrimary,
        focusedLabelColor = AppColors.TextSecondary,
        unfocusedLabelColor = AppColors.TextMuted,
        cursorColor = AppColors.TextPrimary,
        focusedContainerColor = AppColors.Surface,
        unfocusedContainerColor = AppColors.Surface,
    )

    Scaffold(
        containerColor = AppColors.Background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "NIE Parent Portal",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "Sign in to sync subject-wise attendance, CIE marks, and lesson plans.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }

            Surface(
                color = AppColors.Surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Your login details are encrypted with Android Keystore and stay on this device only. Nothing is sent to any cloud or third-party server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(14.dp),
                )
            }

            OutlinedTextField(
                value = usn,
                onValueChange = { usn = it.uppercase() },
                label = { Text("Student USN") },
                singleLine = true,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Date of Birth",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = dobDay,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) dobDay = it },
                        label = { Text("DD") },
                        singleLine = true,
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dobMonth,
                        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) dobMonth = it },
                        label = { Text("MM") },
                        singleLine = true,
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dobYear,
                        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) dobYear = it },
                        label = { Text("YYYY") },
                        singleLine = true,
                        colors = textFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Parent / Guardian Mobile Relation",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(
                        StudentCredentials.RELATION_FATHER to "Father",
                        StudentCredentials.RELATION_MOTHER to "Mother",
                        StudentCredentials.RELATION_GUARDIAN to "Guardian",
                    ).forEach { (value, label) ->
                        val selected = relation == value
                        Surface(
                            color = if (selected) AppColors.TextPrimary else AppColors.SurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) AppColors.TextPrimary else AppColors.BorderSubtle,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { relation = value },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 10.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) AppColors.Background else AppColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = digits,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) digits = it },
                label = { Text("Parent Mobile Last 4 Digits") },
                singleLine = true,
                colors = textFieldColors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            val displayError = localValidationError ?: snapshot.errorMessage
            if (displayError != null) {
                val prefix = when (snapshot.errorKind) {
                    LoginErrorKind.WRONG_DETAILS -> "Wrong login details: "
                    LoginErrorKind.NETWORK_ERROR -> "Network problem: "
                    LoginErrorKind.CHALLENGE_REQUIRED -> "Verification required: "
                    LoginErrorKind.AUTH_REQUIRED -> "Device unlock required: "
                    LoginErrorKind.NONE -> ""
                }
                Surface(
                    color = AppColors.DangerRedSubtle,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AppColors.DangerRed.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "$prefix$displayError",
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    val day = dobDay.toIntOrNull()
                    val month = dobMonth.toIntOrNull()
                    val year = dobYear.toIntOrNull()
                    when {
                        usn.isBlank() -> localValidationError = "Enter your USN."
                        day == null || day !in 1..31 -> localValidationError = "Enter a valid day (01–31)."
                        month == null || month !in 1..12 -> localValidationError = "Enter a valid month (01–12)."
                        year == null || year !in 1960..2026 -> localValidationError = "Enter a valid 4-digit year."
                        digits.length != 4 -> localValidationError = "Enter all 4 digits of the parent mobile number."
                        else -> {
                            localValidationError = null
                            onSubmit(
                                StudentCredentials(
                                    usn = usn.trim(),
                                    dobDay = day.toString().padStart(2, '0'),
                                    dobMonth = month.toString().padStart(2, '0'),
                                    dobYear = year.toString(),
                                    relation = relation,
                                    digits = digits,
                                ),
                            )
                        }
                    }
                },
                enabled = !snapshot.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.TextPrimary,
                    contentColor = AppColors.Background,
                    disabledContainerColor = AppColors.SurfaceElevated,
                    disabledContentColor = AppColors.TextMuted,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (snapshot.isBusy) {
                    CircularProgressIndicator(
                        color = AppColors.TextPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text("Log in")
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewLoginScreen() {
    AttendanceTrackerTheme {
        LoginScreen(
            snapshot = AuthSessionSnapshot(state = SessionState.FIRST_LOGIN),
            onSubmit = {},
        )
    }
}
