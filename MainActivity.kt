package com.example.exploradoreswallet

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "participants")
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tickets",
    foreignKeys = [
        ForeignKey(
            entity = ParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("participantId")]
)
data class TicketEntity(
    @PrimaryKey val barcode: String,
    val participantId: Long,
    val scannedAt: Long = System.currentTimeMillis()
)

data class ParticipantWithPoints(val id: Long, val name: String, val points: Int)
data class TicketWithOwner(val barcode: String, val participantId: Long, val participantName: String, val scannedAt: Long)

@Dao
interface WalletDao {
    @Query("SELECT p.id, p.name, COUNT(t.barcode) AS points FROM participants p LEFT JOIN tickets t ON p.id = t.participantId GROUP BY p.id ORDER BY points DESC, p.name ASC")
    fun getParticipantsWithPoints(): Flow<List<ParticipantWithPoints>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertParticipant(participant: ParticipantEntity): Long

    @Delete
    suspend fun deleteParticipant(participant: ParticipantEntity)

    @Query("SELECT t.barcode, t.participantId, p.name AS participantName, t.scannedAt FROM tickets t INNER JOIN participants p ON t.participantId = p.id WHERE t.barcode = :barcode LIMIT 1")
    suspend fun getTicketWithOwner(barcode: String): TicketWithOwner?

    @Query("SELECT * FROM tickets WHERE participantId = :participantId ORDER BY scannedAt DESC")
    fun getTicketsForParticipant(participantId: Long): Flow<List<TicketEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTicket(ticket: TicketEntity)

    @Query("DELETE FROM tickets WHERE barcode = :barcode")
    suspend fun deleteTicketByBarcode(barcode: String)
}

@Database(entities = [ParticipantEntity::class, TicketEntity::class], version = 1, exportSchema = false)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao

    companion object {
        @Volatile
        private var INSTANCE: WalletDatabase? = null

        fun getDatabase(context: Context): WalletDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    WalletDatabase::class.java,
                    "exploradores_wallet.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

object TicketValidator {
    private const val SECRET_SALT = "ExploradoresClub_ClaveSecreta"
    const val STRICT_VALIDATION = false

    fun isValidTicket(barcode: String): Boolean {
        if (!STRICT_VALIDATION) return true
        val parts = barcode.split("-")
        if (parts.size != 3 || parts[0] != "EXP") return false
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest("${parts[1]}$SECRET_SALT".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(2).uppercase()
        return parts[2] == expectedHash
    }
}

sealed class ScanResultState {
    object Idle : ScanResultState()
    data class NewTicket(val barcode: String) : ScanResultState()
    data class AlreadyAssigned(val ticketInfo: TicketWithOwner) : ScanResultState()
    data class InvalidTicket(val barcode: String) : ScanResultState()
}

class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = WalletDatabase.getDatabase(application).walletDao()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val participants: StateFlow<List<ParticipantWithPoints>> = combine(
        dao.getParticipantsWithPoints(),
        _searchQuery
    ) { list, query ->
        if (query.isBlank()) list else list.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanResult = MutableStateFlow<ScanResultState>(ScanResultState.Idle)
    val scanResult: StateFlow<ScanResultState> = _scanResult.asStateFlow()

    fun updateSearchQuery(query: String) { _searchQuery.value = query }

    fun addParticipant(name: String, onSuccess: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            dao.insertParticipant(ParticipantEntity(name = name.trim()))
            onSuccess()
        }
    }

    fun deleteParticipant(participant: ParticipantWithPoints) {
        viewModelScope.launch {
            dao.deleteParticipant(ParticipantEntity(id = participant.id, name = participant.name))
        }
    }

    fun onBarcodeScanned(rawValue: String) {
        val code = rawValue.trim()
        if (!TicketValidator.isValidTicket(code)) {
            _scanResult.value = ScanResultState.InvalidTicket(code)
            return
        }
        viewModelScope.launch {
            val existing = dao.getTicketWithOwner(code)
            _scanResult.value = if (existing != null) {
                ScanResultState.AlreadyAssigned(existing)
            } else {
                ScanResultState.NewTicket(code)
            }
        }
    }

    fun assignTicket(barcode: String, participantId: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            dao.insertTicket(TicketEntity(barcode = barcode, participantId = participantId))
            _scanResult.value = ScanResultState.Idle
            onComplete()
        }
    }

    fun revokeTicket(barcode: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            dao.deleteTicketByBarcode(barcode)
            _scanResult.value = ScanResultState.Idle
            onComplete()
        }
    }

    fun dismissScanState() { _scanResult.value = ScanResultState.Idle }
    fun getTicketsForParticipant(participantId: Long): Flow<List<TicketEntity>> = dao.getTicketsForParticipant(participantId)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ExploradoresWalletScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploradoresWalletScreen(vm: WalletViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val participants by vm.participants.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val scanState by vm.scanResult.collectAsStateWithLifecycle()

    var showAddParticipantDialog by remember { mutableStateOf(false) }
    var selectedParticipantForDetails by remember { mutableStateOf<ParticipantWithPoints?>(null) }

    val scanner = remember {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }

    val totalPoints = remember(participants) { participants.sumOf { it.points } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Exploradores Wallet", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${participants.size} participantes · $totalPoints pts activos",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { showAddParticipantDialog = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Añadir participante")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (participants.isEmpty() && searchQuery.isEmpty()) {
                        scope.launch { snackbarHostState.showSnackbar("Añade al menos un participante primero") }
                        return@ExtendedFloatingActionButton
                    }
                    scanner.startScan()
                        .addOnSuccessListener { barcode -> barcode.rawValue?.let { vm.onBarcodeScanned(it) } }
                        .addOnFailureListener { e ->
                            scope.launch { snackbarHostState.showSnackbar("Aviso: ${e.localizedMessage}") }
                        }
                },
                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                text = { Text("Escanear Ticket") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = vm::updateSearchQuery,
                placeholder = { Text("Buscar participante...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (participants.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "No hay participantes todavía" else "Sin resultados",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(participants, key = { it.id }) { participant ->
                        ParticipantCard(
                            participant = participant,
                            onClick = { selectedParticipantForDetails = participant }
                        )
                    }
                }
            }
        }
    }

    if (showAddParticipantDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddParticipantDialog = false },
            title = { Text("Nuevo participante") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre completo") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.addParticipant(name) {
                        showAddParticipantDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Participante añadido") }
                    }
                }) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showAddParticipantDialog = false }) { Text("Cancelar") }
            }
        )
    }

    when (val state = scanState) {
        is ScanResultState.NewTicket -> {
            ModalBottomSheet(onDismissRequest = vm::dismissScanState) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text("Asignar Ticket", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Código: ${state.barcode} (+1 Punto)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(participants) { p ->
                            ListItem(
                                headlineContent = { Text(p.name, fontWeight = FontWeight.Medium) },
                                trailingContent = {
                                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text("${p.points} pts", modifier = Modifier.padding(4.dp))
                                    }
                                },
                                modifier = Modifier.clickable {
                                    vm.assignTicket(state.barcode, p.id) {
                                        scope.launch { snackbarHostState.showSnackbar("+1 punto sumado a ${p.name}") }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        is ScanResultState.AlreadyAssigned -> {
            val info = state.ticketInfo
            AlertDialog(
                onDismissRequest = vm::dismissScanState,
                icon = { Icon(Icons.Outlined.ConfirmationNumber, contentDescription = null) },
                title = { Text("Ticket ya registrado") },
                text = {
                    Text(
                        "El ticket (${info.barcode}) ya pertenece a ${info.participantName}.\n\n" +
                        "¿Deseas descontar este ticket y restar 1 punto de su wallet?"
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            vm.revokeTicket(info.barcode) {
                                scope.launch { snackbarHostState.showSnackbar("-1 punto restado a ${info.participantName}") }
                            }
                        }
                    ) { Text("Restar 1 punto") }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissScanState) { Text("Cancelar") }
                }
            )
        }

        is ScanResultState.InvalidTicket -> {
            AlertDialog(
                onDismissRequest = vm::dismissScanState,
                title = { Text("Código no válido") },
                text = { Text("El código '${state.barcode}' no es un ticket válido.") },
                confirmButton = { TextButton(onClick = vm::dismissScanState) { Text("Entendido") } }
            )
        }

        ScanResultState.Idle -> Unit
    }

    selectedParticipantForDetails?.let { participant ->
        val participantTickets by vm.getTicketsForParticipant(participant.id)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

        ModalBottomSheet(onDismissRequest = { selectedParticipantForDetails = null }) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(participant.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${participantTickets.size} puntos acumulados", color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        vm.deleteParticipant(participant)
                        selectedParticipantForDetails = null
                        scope.launch { snackbarHostState.showSnackbar("${participant.name} eliminado") }
                    }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (participantTickets.isEmpty()) {
                    Text(
                        "No tiene tickets asignados actualmente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(participantTickets, key = { it.barcode }) { ticket ->
                            ListItem(
                                headlineContent = { Text("Ticket #${ticket.barcode}") },
                                supportingContent = { Text(dateFormat.format(Date(ticket.scannedAt))) },
                                trailingContent = {
                                    TextButton(onClick = {
                                        vm.revokeTicket(ticket.barcode) {
                                            scope.launch { snackbarHostState.showSnackbar("Ticket ${ticket.barcode} restado") }
                                        }
                                    }) {
                                        Text("-1 pt", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun ParticipantCard(participant: ParticipantWithPoints, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = participant.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(participant.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text("Toca para ver sus tickets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                shape = CircleShape,
                color = if (participant.points > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "${participant.points} pts",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (participant.points > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
