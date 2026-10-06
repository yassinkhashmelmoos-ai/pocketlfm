package com.thunder.agi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.thunder.agi.ui.theme.*

// ── First Launch — Username Entry ─────────────────────────────────────────────

@Composable
fun OnboardingScreen(
    onRegister: (String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(ThunderBlack)
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⚡", fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text("Thunder AGI", style = MaterialTheme.typography.headlineMedium,
            color = ThunderElectric, fontWeight = FontWeight.Bold)
        Text("الرَّعد للذكاء العام المصطنع",
            style = MaterialTheme.typography.titleMedium, color = ThunderGray)
        Spacer(Modifier.height(8.dp))
        Text("بسم الله الرحمن الرحيم",
            style = MaterialTheme.typography.bodyMedium, color = ThunderGray,
            textAlign = TextAlign.Center)

        Spacer(Modifier.height(40.dp))

        Text("أدخل اسم المستخدم للبدء",
            style = MaterialTheme.typography.titleSmall, color = ThunderWhite)
        Spacer(Modifier.height(8.dp))
        Text("تحصل على شهر كامل مجاناً",
            style = MaterialTheme.typography.bodySmall, color = ThunderGreen)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = "" },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("اسم المستخدم", color = ThunderGray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.None
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ThunderElectric,
                unfocusedBorderColor = ThunderMidBlue,
                focusedTextColor = ThunderWhite,
                unfocusedTextColor = ThunderWhite,
                cursorColor = ThunderElectric,
                focusedContainerColor = ThunderDeepBlue,
                unfocusedContainerColor = ThunderDeepBlue
            ),
            shape = RoundedCornerShape(12.dp)
        )

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = ThunderRed, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (username.trim().isBlank()) {
                    error = "يرجى إدخال اسم المستخدم"
                } else {
                    onRegister(username.trim())
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("ابدأ الاستخدام المجاني", color = ThunderBlack,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(32.dp))
        Text("تطبيق سوداني المنشأ • يعمل بدون إنترنت",
            style = MaterialTheme.typography.labelSmall, color = ThunderGray,
            textAlign = TextAlign.Center)
    }
}

// ── Trial Expired — Payment Screen ────────────────────────────────────────────

@Composable
fun PaymentScreen(
    username: String,
    onVerifyReceipt: (Uri, String, String) -> Unit,
    onResetKey: (String) -> Unit,
    verificationMessage: String,
    isVerifying: Boolean
) {
    var processNumber by remember { mutableStateOf("") }
    var receiptDate   by remember { mutableStateOf("") }
    var receiptUri    by remember { mutableStateOf<Uri?>(null) }
    var showResetKey  by remember { mutableStateOf(false) }
    var resetKey      by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> receiptUri = uri }

    Column(
        Modifier
            .fillMaxSize()
            .background(ThunderBlack)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text("⚡", fontSize = 48.sp)
        Spacer(Modifier.height(8.dp))
        Text("Thunder AGI", style = MaterialTheme.typography.headlineSmall,
            color = ThunderElectric, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))

        // Payment info card
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
            border = BorderStroke(1.dp, ThunderElectric)
        ) {
            Column(Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("انتهت فترة الاستخدام المجاني",
                    style = MaterialTheme.typography.titleMedium,
                    color = ThunderWhite, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("لمواصلة الاستخدام، حوّل أي مبلغ تختاره إلى:",
                    color = ThunderGray, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text("بنك الخرطوم — بنكك",
                    color = ThunderElectric, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Text("رقم الحساب: 2587655",
                    color = ThunderWhite, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("ثم ارفع صورة الإيصال أدناه",
                    color = ThunderGray, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Receipt upload
        Text("رفع إيصال الدفع", color = ThunderWhite,
            style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        // Image picker
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ThunderDeepBlue)
                .border(1.dp,
                    if (receiptUri != null) ThunderGreen else ThunderMidBlue,
                    RoundedCornerShape(12.dp))
                .clickable { imagePicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (receiptUri != null) {
                AsyncImage(model = receiptUri, contentDescription = null,
                    modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Upload, null, tint = ThunderElectric,
                        modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("اضغط لرفع صورة الإيصال",
                        color = ThunderGray, style = MaterialTheme.typography.bodySmall)
                    Text("(صورة خضراء أو بيضاء من بنكك)",
                        color = ThunderGray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Process number input
        OutlinedTextField(
            value = processNumber,
            onValueChange = { processNumber = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("رقم العملية (من الإيصال)", color = ThunderGray) },
            placeholder = { Text("مثال: 20379363859", color = ThunderGray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = paymentFieldColors()
        )

        Spacer(Modifier.height(12.dp))

        // Receipt date input
        OutlinedTextField(
            value = receiptDate,
            onValueChange = { receiptDate = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("تاريخ الإيصال", color = ThunderGray) },
            placeholder = { Text("مثال: 03-Oct-2026", color = ThunderGray) },
            singleLine = true,
            colors = paymentFieldColors()
        )

        Spacer(Modifier.height(16.dp))

        // Verify button
        Button(
            onClick = {
                receiptUri?.let { uri ->
                    onVerifyReceipt(uri, processNumber, receiptDate)
                }
            },
            enabled = receiptUri != null && processNumber.isNotBlank() &&
                    receiptDate.isNotBlank() && !isVerifying,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isVerifying) {
                CircularProgressIndicator(Modifier.size(20.dp), color = ThunderBlack, strokeWidth = 2.dp)
            } else {
                Text("تحقق من الإيصال وفعّل الاشتراك",
                    color = ThunderBlack, fontWeight = FontWeight.Bold)
            }
        }

        // Verification result
        if (verificationMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            val isSuccess = verificationMessage.contains("بنجاح")
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuccess)
                        ThunderGreen.copy(alpha = 0.15f)
                    else ThunderRed.copy(alpha = 0.15f)
                )
            ) {
                Text(verificationMessage,
                    Modifier.padding(16.dp),
                    color = if (isSuccess) ThunderGreen else ThunderRed,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = ThunderMidBlue)
        Spacer(Modifier.height(16.dp))

        // Reset key option
        TextButton(onClick = { showResetKey = !showResetKey }) {
            Text("لديك مفتاح إعادة تفعيل؟",
                color = ThunderGray, style = MaterialTheme.typography.labelMedium)
        }

        AnimatedVisibility(showResetKey) {
            Column {
                OutlinedTextField(
                    value = resetKey,
                    onValueChange = { resetKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("أدخل مفتاح إعادة التفعيل", color = ThunderGray) },
                    singleLine = true,
                    colors = paymentFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onResetKey(resetKey) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, ThunderPurple)
                ) {
                    Text("تطبيق المفتاح", color = ThunderPurple)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("للتواصل: thunderagi.app@gmail.com",
            style = MaterialTheme.typography.labelSmall, color = ThunderGray,
            textAlign = TextAlign.Center)
    }
}

// ── About Screen ──────────────────────────────────────────────────────────────

@Composable
fun AboutScreen(onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ThunderBlack)
            .verticalScroll(rememberScrollState())
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(ThunderDeepBlue)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null, tint = ThunderElectric)
            }
            Text("حول التطبيق", color = ThunderWhite,
                style = MaterialTheme.typography.titleMedium)
        }

        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Logo + name
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚡", fontSize = 64.sp)
                Text("Thunder AGI",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ThunderElectric, fontWeight = FontWeight.Bold)
                Text("الرَّعد للذكاء العام المصطنع",
                    style = MaterialTheme.typography.titleMedium,
                    color = ThunderGray)
            }

            HorizontalDivider(color = ThunderMidBlue)

            // Arabic description
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)
            ) {
                Text(
                    """بسم الله الرحمن الرحيم

تطبيق الرَّعد للذكاء العام المصطنع هو تطبيق سوداني المنشأ بالدفع الآجل وهو تطبيق ذو صلاحيات كبيرة وموزع يعمل بدون إنترنت وبالإنترنت وبالتواصل مع بقية التطبيقات عند مختلف المستخدمين، يستعمل معلومات المستخدم وطبيعتها لبناء شخصية رقمية عنه ومشاركة مهارات المستخدم مع بقية المستخدمين من خلال التطبيق الموزع حيث يتطور النموذج من التفاعل مع المستخدمين وغيره ويستطيع أداء مختلف المهام الموكلة إليه.""",
                    Modifier.padding(20.dp),
                    color = ThunderWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Right,
                    lineHeight = 26.sp
                )
            }

            HorizontalDivider(color = ThunderMidBlue)

            // Technical info
            InfoRow("الإصدار", "v3.0 — Thunder AGI")
            InfoRow("النموذج", "LFM2.5-VL-450M (Q4_K_M)")
            InfoRow("المطور", "ياسين خشيلموس")
            InfoRow("تاريخ الإصدار", "23/6/2026")
            InfoRow("اللغات", "العربية • English")

            HorizontalDivider(color = ThunderMidBlue)

            // Contact
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)
            ) {
                Column(Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("يمكنك التواصل معنا عبر الإيميل:",
                        color = ThunderGray, style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text("thunderagi.app@gmail.com",
                        color = ThunderElectric,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
            }

            // License note
            Text(
                "© 2026 ياسين خشيلموس — جميع الحقوق محفوظة\n" +
                "Thunder AGI Proprietary License",
                color = ThunderGray,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = ThunderGray, style = MaterialTheme.typography.bodySmall)
        Text(value, color = ThunderWhite, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun paymentFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ThunderElectric,
    unfocusedBorderColor = ThunderMidBlue,
    focusedTextColor = ThunderWhite,
    unfocusedTextColor = ThunderWhite,
    cursorColor = ThunderElectric,
    focusedContainerColor = ThunderDeepBlue,
    unfocusedContainerColor = ThunderDeepBlue,
    focusedLabelColor = ThunderElectric,
    unfocusedLabelColor = ThunderGray
)
