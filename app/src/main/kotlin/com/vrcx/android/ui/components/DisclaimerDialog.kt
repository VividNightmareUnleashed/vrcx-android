package com.vrcx.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vrcx.android.ui.theme.AnthropicDark
import com.vrcx.android.ui.theme.AnthropicLight
import com.vrcx.android.ui.theme.AnthropicMidGray
import com.vrcx.android.ui.theme.AnthropicOrange
import kotlinx.coroutines.delay

@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onExit: () -> Unit,
) {
    var countdown by remember { mutableIntStateOf(1) }
    val acceptEnabled = countdown <= 0

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000L)
            countdown--
        }
    }

    val bodyStyle = SpanStyle(color = AnthropicLight, fontFamily = FontFamily.Serif, fontSize = 15.sp)

    val aiDisclosure = remember {
        buildAnnotatedString {
            withStyle(bodyStyle) {
                append("VRCX Android was made with ")
                withLink(
                    LinkAnnotation.Url(
                        url = "https://claude.ai",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = AnthropicOrange,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    )
                ) {
                    append("Claude")
                }
                append(" mainly for personal use. While no AI-generated art was specifically used for this (which most people take issue with), I understand some would rather keep away from any AI-assisted work.")
            }
        }
    }

    val independence = remember {
        buildAnnotatedString {
            withStyle(bodyStyle.copy(fontWeight = FontWeight.Bold)) {
                append("This project is independent and not affiliated with the VRCX Team.")
            }
        }
    }

    val noGuarantee = remember {
        buildAnnotatedString {
            withStyle(bodyStyle.copy(color = AnthropicMidGray, fontSize = 13.sp)) {
                append("VRCX Android does not come with any guarantee of functionality. It is fully free and always will be.")
            }
        }
    }

    Dialog(
        onDismissRequest = { /* non-dismissible */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            color = AnthropicDark,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = "VRCX Android",
                    color = AnthropicLight,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(16.dp))

                Text(text = aiDisclosure, lineHeight = 22.sp)

                Spacer(Modifier.height(12.dp))

                Text(text = independence, lineHeight = 22.sp)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AnthropicMidGray.copy(alpha = 0.3f))
                Spacer(Modifier.height(12.dp))

                Text(text = noGuarantee, lineHeight = 18.sp)

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onExit) {
                        Text("Exit", color = AnthropicMidGray)
                    }

                    Button(
                        onClick = onAccept,
                        enabled = acceptEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AnthropicOrange,
                            contentColor = AnthropicDark,
                            disabledContainerColor = AnthropicMidGray.copy(alpha = 0.3f),
                            disabledContentColor = AnthropicMidGray,
                        ),
                    ) {
                        Text(
                            text = if (acceptEnabled) "Accept" else "Accept ($countdown)",
                        )
                    }
                }
            }
        }
    }
}
