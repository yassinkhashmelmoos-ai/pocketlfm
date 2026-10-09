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
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.thunder.agi.R
import com.thunder.agi.ui.theme.*

// ── Activation Screen — License Key OR Bank of Khartoum Receipt ──────────────

@Composable
fun ActivationScreen(
    // License key path
    onActivateLicense: (username: String, key: String) -> Boolean,
    // Bank receipt path (existing logic)
    onVerifyReceipt: (Uri, String, String) -> Unit,
    receiptVerificationMessage: String,
    isVerifyingReceipt: Boolean,
    // Status message from license path
    statusMessage: String = ""
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ThunderBlack)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Header
        Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = "Thunder AGI",
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("Thunder AGI", style = MaterialTheme.typography.headlineMedium,
            color = ThunderElectric, fontWeight = FontWeight.Bold)
        Text("الرَّعد للذكاء العام المصطنع",
            style = MaterialTheme.typography.titleMedium, color = ThunderGray)
        Spacer(Modifier.height(4.dp))
        Text("بسم الله الرحمن الرحيم",
            style = MaterialTheme.typography.bodySmall, color = ThunderGray)

        Spacer(Modifier.height(32.dp))

        Text("تفعيل التطبيق", style = MaterialTheme.typography.titleMedium,
            color = ThunderWhite, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("اختر طريقة التفعيل",
            style = MaterialTheme.typography.bodySmall, color = ThunderGray)

        Spacer(Modifier.height(16.dp))

        // Tab selector
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = ThunderDeepBlue,
            contentColor = ThunderElectric,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("مفتاح الترخيص", color = if (selectedTab == 0) ThunderElectric else ThunderGray) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("إيصال بنك الخرطوم", color = if (selectedTab == 1) ThunderElectric else ThunderGray) }
            )
        }

        Spacer(Modifier.height(24.dp))

        when (selectedTab) {
            0 -> LicenseKeyTab(onActivateLicense = onActivateLicense, statusMessage = statusMessage)
            1 -> BankReceiptTab(
                onVerifyReceipt = onVerifyReceipt,
                verificationMessage = receiptVerificationMessage,
                isVerifying = isVerifyingReceipt
            )
        }

        Spacer(Modifier.height(32.dp))
        Text("تطبيق سوداني المنشأ • يعمل بدون إنترنت",
            style = MaterialTheme.typography.labelSmall, color = ThunderGray,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LicenseKeyTab(
    onActivateLicense: (String, String) -> Boolean,
    statusMessage: String
) {
    var username   by remember { mutableStateOf("") }
    var licenseKey by remember { mutableStateOf("") }
    var error      by remember { mutableStateOf("") }
    var success    by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor     = ThunderElectric,
            unfocusedBorderColor   = ThunderMidBlue,
            focusedTextColor       = ThunderWhite,
            unfocusedTextColor     = ThunderWhite,
            cursorColor            = ThunderElectric,
            focusedContainerColor  = ThunderDeepBlue,
            unfocusedContainerColor = ThunderDeepBlue
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = ""; success = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("اسم المستخدم", color = ThunderGray) },
            singleLine = true,
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it; error = ""; success = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("مفتاح الترخيص", color = ThunderGray) },
            singleLine = true,
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            )
        )

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = ThunderRed, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center)
        }
        if (success) {
            Spacer(Modifier.height(8.dp))
            Text("✅ تم التفعيل بنجاح!", color = ThunderGreen,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                when {
                    username.trim().isBlank() -> error = "يرجى إدخال اسم المستخدم"
                    licenseKey.trim().isBlank() -> error = "يرجى إدخال مفتاح الترخيص"
                    else -> {
                        val ok = onActivateLicense(username.trim(), licenseKey.trim())
                        if (ok) success = true
                        else error = "مفتاح غير صحيح أو منتهي الصلاحية"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("تفعيل", color = ThunderBlack,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BankReceiptTab(
    onVerifyReceipt: (Uri, String, String) -> Unit,
    verificationMessage: String,
    isVerifying: Boolean
) {
    var processNumber by remember { mutableStateOf("") }
    var receiptDate   by remember { mutableStateOf("") }
    var receiptUri    by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> receiptUri = uri }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ارفع إيصال بنك الخرطوم",
            style = MaterialTheme.typography.titleSmall, color = ThunderWhite)
        Spacer(Modifier.height(4.dp))
        Text("يتم مراجعة الإيصال تلقائياً بالذكاء الاصطناعي",
            style = MaterialTheme.typography.bodySmall, color = ThunderGray,
            textAlign = TextAlign.Center)

        Spacer(Modifier.height(16.dp))

        // Receipt image picker
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ThunderDeepBlue)
                .border(2.dp, if (receiptUri != null) ThunderGreen else ThunderMidBlue, RoundedCornerShape(12.dp))
                .clickable { picker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (receiptUri != null) {
                AsyncImage(model = receiptUri, contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AddPhotoAlternate, null, tint = ThunderGray,
                        modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("اضغط لاختيار صورة الإيصال",
                        color = ThunderGray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ThunderElectric,
            unfocusedBorderColor = ThunderMidBlue,
            focusedTextColor = ThunderWhite,
            unfocusedTextColor = ThunderWhite,
            cursorColor = ThunderElectric,
            focusedContainerColor = ThunderDeepBlue,
            unfocusedContainerColor = ThunderDeepBlue
        )

        OutlinedTextField(
            value = processNumber,
            onValueChange = { processNumber = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("رقم العملية", color = ThunderGray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fieldColors, shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = receiptDate,
            onValueChange = { receiptDate = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("تاريخ الإيصال (YYYY-MM-DD)", color = ThunderGray) },
            singleLine = true,
            colors = fieldColors, shape = RoundedCornerShape(12.dp)
        )

        if (verificationMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(verificationMessage,
                color = if (verificationMessage.startsWith("✅")) ThunderGreen else ThunderRed,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                receiptUri?.let { uri ->
                    onVerifyReceipt(uri, processNumber, receiptDate)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = receiptUri != null && processNumber.isNotBlank() &&
                      receiptDate.isNotBlank() && !isVerifying,
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isVerifying) {
                CircularProgressIndicator(Modifier.size(20.dp), color = ThunderBlack, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isVerifying) "جارٍ التحقق…" else "تحقق من الإيصال",
                color = ThunderBlack, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
        }
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
        Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = "Thunder AGI",
            modifier = Modifier.size(72.dp)
        )
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
                Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = "Thunder AGI",
            modifier = Modifier.size(80.dp)
        )
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
