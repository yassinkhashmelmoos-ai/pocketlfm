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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.thunder.agi.R
import com.thunder.agi.ui.theme.*

// ── Activation Screen ─────────────────────────────────────────────────────────
@Composable
fun ActivationScreen(
    onActivateLicense:          (username: String, key: String) -> Boolean,
    onVerifyReceipt:            (Uri, String, String) -> Unit,
    receiptVerificationMessage: String,
    isVerifyingReceipt:         Boolean,
    statusMessage:              String = ""
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

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor   = ThunderDeepBlue,
            contentColor     = ThunderElectric,
            modifier         = Modifier.padding(horizontal = 24.dp)
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                text = { Text("مفتاح الترخيص", color = if (selectedTab == 0) ThunderElectric else ThunderGray) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                text = { Text("إيصال بنك الخرطوم", color = if (selectedTab == 1) ThunderElectric else ThunderGray) })
        }

        Spacer(Modifier.height(24.dp))

        when (selectedTab) {
            0 -> LicenseKeyTab(onActivateLicense = onActivateLicense, statusMessage = statusMessage)
            1 -> BankReceiptTab(
                onVerifyReceipt    = onVerifyReceipt,
                verificationMessage = receiptVerificationMessage,
                isVerifying        = isVerifyingReceipt
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
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val fieldColors = fieldColors()

        val uriHandlerLic = LocalUriHandler.current
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
            border = BorderStroke(1.dp, ThunderGreen.copy(alpha = 0.4f))
        ) {
            Row(Modifier
                .clickable { uriHandlerLic.openUri("https://wa.me/message/thunder.agi") }
                .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("للحصول على مفتاح الترخيص:", color = ThunderGray,
                        style = MaterialTheme.typography.labelSmall)
                    Text("واتساب: @thunder.agi", color = ThunderGreen,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = username, onValueChange = { username = it; error = ""; success = false },
            modifier = Modifier.fillMaxWidth(), label = { Text("اسم المستخدم", color = ThunderGray) },
            singleLine = true, colors = fieldColors, shape = RoundedCornerShape(12.dp))

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = licenseKey, onValueChange = { licenseKey = it; error = ""; success = false },
            modifier = Modifier.fillMaxWidth(), label = { Text("مفتاح الترخيص", color = ThunderGray) },
            singleLine = true, colors = fieldColors, shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done))

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = ThunderRed, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        if (success) {
            Spacer(Modifier.height(8.dp))
            Text("✅ تم التفعيل بنجاح!", color = ThunderGreen, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            when {
                username.trim().isBlank()   -> error = "يرجى إدخال اسم المستخدم"
                licenseKey.trim().isBlank() -> error = "يرجى إدخال مفتاح الترخيص"
                else -> {
                    val ok = onActivateLicense(username.trim(), licenseKey.trim())
                    if (ok) success = true else error = "مفتاح غير صحيح أو منتهي الصلاحية"
                }
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric),
            shape = RoundedCornerShape(12.dp)) {
            Text("تفعيل", color = ThunderBlack,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BankReceiptTab(
    onVerifyReceipt:     (Uri, String, String) -> Unit,
    verificationMessage: String,
    isVerifying:         Boolean
) {
    var processNumber by remember { mutableStateOf("") }
    var receiptDate   by remember { mutableStateOf("") }
    var receiptUri    by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        receiptUri = uri
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ارفع إيصال بنك الخرطوم",
            style = MaterialTheme.typography.titleSmall, color = ThunderWhite)
        Spacer(Modifier.height(4.dp))
        Text("يتم مراجعة الإيصال تلقائياً بالذكاء الاصطناعي",
            style = MaterialTheme.typography.bodySmall, color = ThunderGray,
            textAlign = TextAlign.Center)

        // FIX: Show payment account number prominently so users know where to pay
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
            border = BorderStroke(1.dp, ThunderElectric)
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ادفع إلى — Pay to:", color = ThunderGray,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Text("بنك الخرطوم — بنكك", color = ThunderElectric,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("رقم الحساب: 2587655",
                    color = ThunderWhite, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text("Account No.: 2587655",
                    color = ThunderGray, style = MaterialTheme.typography.bodySmall)
            }
        }

        // WhatsApp support card
        Spacer(Modifier.height(8.dp))
        val uriHandler = LocalUriHandler.current
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
            border = BorderStroke(1.dp, ThunderGreen.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("للحصول على مفاتيح الدخول أو الدعم، تواصل معنا:\nFor support contact:",
                    color = ThunderGray, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
                Row(Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ThunderGreen.copy(alpha = 0.15f))
                    .clickable { uriHandler.openUri("https://wa.me/message/thunder.agi") }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("📱", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("واتساب: @thunder.agi", color = ThunderGreen,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Image picker
        Box(
            Modifier.fillMaxWidth().height(160.dp)
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
                    Icon(Icons.Default.AddPhotoAlternate, null, tint = ThunderGray, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("اضغط لاختيار صورة الإيصال", color = ThunderGray,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val fColors = fieldColors()
        OutlinedTextField(value = processNumber, onValueChange = { processNumber = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("رقم العملية (من الإيصال)", color = ThunderGray) },
            placeholder = { Text("مثال: 20229627131", color = ThunderGray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fColors, shape = RoundedCornerShape(12.dp))

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = receiptDate, onValueChange = { receiptDate = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("تاريخ الإيصال (YYYY-MM-DD)", color = ThunderGray) },
            placeholder = { Text("مثال: 2026-10-11", color = ThunderGray) },
            singleLine = true, colors = fColors, shape = RoundedCornerShape(12.dp))

        if (verificationMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(verificationMessage,
                color = if (verificationMessage.startsWith("✅") || verificationMessage.contains("بنجاح"))
                    ThunderGreen else ThunderRed,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            receiptUri?.let { uri -> onVerifyReceipt(uri, processNumber, receiptDate) }
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

// ── Payment Screen ────────────────────────────────────────────────────────────
@Composable
fun PaymentScreen(
    username:            String,
    onVerifyReceipt:     (Uri, String, String) -> Unit,
    onResetKey:          (String) -> Unit,
    verificationMessage: String,
    isVerifying:         Boolean
) {
    var processNumber by remember { mutableStateOf("") }
    var receiptDate   by remember { mutableStateOf("") }
    var receiptUri    by remember { mutableStateOf<Uri?>(null) }
    var showResetKey  by remember { mutableStateOf(false) }
    var resetKey      by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        receiptUri = uri
    }

    Column(
        Modifier.fillMaxSize().background(ThunderBlack)
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Image(painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = "Thunder AGI", modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(8.dp))
        Text("Thunder AGI", style = MaterialTheme.typography.headlineSmall,
            color = ThunderElectric, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
            border = BorderStroke(1.dp, ThunderElectric)
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("انتهت فترة الاستخدام المجاني",
                    style = MaterialTheme.typography.titleMedium,
                    color = ThunderWhite, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
        Text("رفع إيصال الدفع", color = ThunderWhite, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ThunderDeepBlue)
            .border(1.dp, if (receiptUri != null) ThunderGreen else ThunderMidBlue, RoundedCornerShape(12.dp))
            .clickable { imagePicker.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (receiptUri != null) {
                AsyncImage(model = receiptUri, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Upload, null, tint = ThunderElectric, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("اضغط لرفع صورة الإيصال", color = ThunderGray,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val pColors = paymentFieldColors()
        OutlinedTextField(value = processNumber, onValueChange = { processNumber = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("رقم العملية (من الإيصال)", color = ThunderGray) },
            placeholder = { Text("مثال: 20379363859", color = ThunderGray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = pColors)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = receiptDate, onValueChange = { receiptDate = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("تاريخ الإيصال", color = ThunderGray) },
            placeholder = { Text("مثال: 2026-10-11", color = ThunderGray) },
            singleLine = true, colors = pColors)

        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            receiptUri?.let { uri -> onVerifyReceipt(uri, processNumber, receiptDate) }
        },
            enabled = receiptUri != null && processNumber.isNotBlank() &&
                      receiptDate.isNotBlank() && !isVerifying,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThunderElectric),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isVerifying) CircularProgressIndicator(Modifier.size(20.dp), color = ThunderBlack, strokeWidth = 2.dp)
            else Text("تحقق من الإيصال وفعّل الاشتراك", color = ThunderBlack, fontWeight = FontWeight.Bold)
        }

        if (verificationMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            val isSuccess = verificationMessage.contains("بنجاح") || verificationMessage.startsWith("✅")
            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuccess) ThunderGreen.copy(0.15f) else ThunderRed.copy(0.15f))
            ) {
                Text(verificationMessage, Modifier.padding(16.dp),
                    color = if (isSuccess) ThunderGreen else ThunderRed,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = ThunderMidBlue)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showResetKey = !showResetKey }) {
            Text("لديك مفتاح إعادة تفعيل؟", color = ThunderGray,
                style = MaterialTheme.typography.labelMedium)
        }
        AnimatedVisibility(showResetKey) {
            Column {
                OutlinedTextField(value = resetKey, onValueChange = { resetKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("أدخل مفتاح إعادة التفعيل", color = ThunderGray) },
                    singleLine = true, colors = pColors)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onResetKey(resetKey) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, ThunderPurple)) {
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
        Modifier.fillMaxSize().background(ThunderBlack).verticalScroll(rememberScrollState())
    ) {
        // FIX: bilingual title "About • عن التطبيق"
        Row(Modifier.fillMaxWidth().background(ThunderDeepBlue).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, null, tint = ThunderElectric)
            }
            Text("About • عن التطبيق", color = ThunderWhite,
                style = MaterialTheme.typography.titleMedium)
        }

        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painter = painterResource(R.mipmap.ic_launcher_round),
                    contentDescription = "Thunder AGI", modifier = Modifier.size(80.dp))
                Text("Thunder AGI", style = MaterialTheme.typography.headlineMedium,
                    color = ThunderElectric, fontWeight = FontWeight.Bold)
                Text("الرَّعد للذكاء العام المصطنع",
                    style = MaterialTheme.typography.titleMedium, color = ThunderGray)
            }

            HorizontalDivider(color = ThunderMidBlue)

            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue)
            ) {
                Text(
                    "بسم الله الرحمن الرحيم\n\n" +
                    "تطبيق الرَّعد للذكاء العام المصطنع هو تطبيق سوداني المنشأ بالدفع الاجل " +
                    "و هو تطبيق ذو صلاحيات كبيرة وموزع يعمل بدون انترنت و بالانترنت وبالتواصل " +
                    "مع بقية التطبيقات عند مختلف المستخدمين، يستعمل معلومات المستخدم و طبيعتها " +
                    "لبناء شخصية رقمية عنه و مشاركة مهارات المستخدم مع بقية المستخدمين من خلال " +
                    "التطبيق الموزع حيث يتطور النموذج من التفاعل مع المستخدمين و غيره و يستطيع " +
                    "اداء مختلف المهام الموكلة إليه.",
                    Modifier.padding(20.dp), color = ThunderWhite,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Right, lineHeight = 26.sp
                )
            }

            HorizontalDivider(color = ThunderMidBlue)

            InfoRow("الإصدار", "v3.0 — Thunder AGI")
            // FIX: model name changed to "Lightning — البرق"
            InfoRow("النموذج / Model", "Lightning — البرق")
            InfoRow("المطور", "ياسين خشيلموس")
            InfoRow("تاريخ الإصدار", "23/6/2026")
            InfoRow("اللغات", "العربية • English")

            HorizontalDivider(color = ThunderMidBlue)

            val uriHandler = LocalUriHandler.current
            Card(Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThunderDeepBlue),
                border = BorderStroke(1.dp, ThunderMidBlue)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("يمكنك التواصل معنا عبر الايميل:", color = ThunderGray,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Text("thunderagi.app@gmail.com", color = ThunderElectric,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { uriHandler.openUri("mailto:thunderagi.app@gmail.com") })

                    HorizontalDivider(color = ThunderMidBlue)

                    Text("أو الواتساب:", color = ThunderGray,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Row(Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ThunderGreen.copy(alpha = 0.15f))
                        .clickable { uriHandler.openUri("https://wa.me/message/thunder.agi") }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Text("📱", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("@thunder.agi", color = ThunderGreen,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text("23/6/2026\n© 2026 ياسين خشيلموس — جميع الحقوق محفوظة",
                color = ThunderGray, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = ThunderGray, style = MaterialTheme.typography.bodySmall)
        Text(value, color = ThunderWhite, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor      = ThunderElectric,
    unfocusedBorderColor    = ThunderMidBlue,
    focusedTextColor        = ThunderWhite,
    unfocusedTextColor      = ThunderWhite,
    cursorColor             = ThunderElectric,
    focusedContainerColor   = ThunderDeepBlue,
    unfocusedContainerColor = ThunderDeepBlue
)

@Composable
private fun paymentFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor      = ThunderElectric,
    unfocusedBorderColor    = ThunderMidBlue,
    focusedTextColor        = ThunderWhite,
    unfocusedTextColor      = ThunderWhite,
    cursorColor             = ThunderElectric,
    focusedContainerColor   = ThunderDeepBlue,
    unfocusedContainerColor = ThunderDeepBlue,
    focusedLabelColor       = ThunderElectric,
    unfocusedLabelColor     = ThunderGray
)
