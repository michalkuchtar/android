package pl.gov.mc.protego.bluetooth.scanner

import android.bluetooth.BluetoothGattCharacteristic
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.exceptions.BleAdapterDisabledException
import com.polidea.rxandroidble2.exceptions.BleGattCharacteristicException
import com.polidea.rxandroidble2.exceptions.BleGattException
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.subjects.BehaviorSubject
import pl.gov.mc.protego.bluetooth.ProteGoCharacteristicUUIDString
import pl.gov.mc.protego.bluetooth.ProteGoServiceUUIDString
import pl.gov.mc.protego.bluetooth.beacon.BeaconId
import pl.gov.mc.protego.bluetooth.beacon.BeaconIdAgent
import pl.gov.mc.protego.bluetooth.beacon.BeaconIdLocal
import pl.gov.mc.protego.bluetooth.beacon.BeaconIdRemote
import timber.log.Timber
import java.util.*

class ProteGoConnector(beaconIdAgent: BeaconIdAgent) {

    private val uuidProteGoService = UUID.fromString(ProteGoServiceUUIDString)
    private val uuidProteGoCharacteristic = UUID.fromString(ProteGoCharacteristicUUIDString)
    private val beaconIdToUseSubject: BehaviorSubject<BeaconIdToUse> = BehaviorSubject.createDefault(BeaconIdToUse.NoBeaconId)
    private val beaconIdAgentListener = object : BeaconIdAgent.Listener {
        override fun useBeaconId(beaconIdLocal: BeaconIdLocal?) {
            val beaconIdToUse = when (beaconIdLocal) {
                null -> BeaconIdToUse.NoBeaconId
                else -> BeaconIdToUse.CurrentBeaconId(beaconIdLocal.beaconId)
            }
            this@ProteGoConnector.beaconIdToUseSubject.onNext(beaconIdToUse)
        }
    }

    init {
        beaconIdAgent.registerListener(beaconIdAgentListener)
    }

    private sealed class DiscoveryProcess {
        object Started : DiscoveryProcess()
        sealed class Finished : DiscoveryProcess() {
            object NotFound : Finished()
            data class Found(val connection: RxBleConnection, val proteGoCharacteristic: BluetoothGattCharacteristic) : Finished()
        }
    }

    private sealed class BeaconIdToUse {
        data class CurrentBeaconId(val beaconId: BeaconId) : BeaconIdToUse()
        object NoBeaconId : BeaconIdToUse()
    }

    fun syncBeaconIds(proteGoPeripheral: ClassifiedPeripheral.ProteGo): Observable<SyncEvent> =
        proteGoPeripheral.bleDevice.establishConnection(true)
            .flatMap { connection ->
                connection.discoverServices()
                    .map<DiscoveryProcess> { services ->
                        services.bluetoothGattServices.find { it.uuid == uuidProteGoService }
                            ?.getCharacteristic(uuidProteGoCharacteristic)
                            ?.let { DiscoveryProcess.Finished.Found(connection, it) }
                            ?: DiscoveryProcess.Finished.NotFound
                    }
                    .toObservable()
                    .startWithArray(DiscoveryProcess.Started)
            }
            .flatMap {
                when (it) {
                    DiscoveryProcess.Started -> Observable.just(SyncEvent.Connection.DiscoveringServices)
                    DiscoveryProcess.Finished.NotFound -> Observable.just(SyncEvent.Process.End.Aborted)
                        .also { Timber.d("[connection] Abort: ProteGoCharacteristic not found for classified peripheral: ${proteGoPeripheral.className()}") }
                    is DiscoveryProcess.Finished.Found -> when (proteGoPeripheral) {
                        is ClassifiedPeripheral.ProteGo.FullAdvertisement -> it.writeLocalBeaconIdOnly()
                        is ClassifiedPeripheral.ProteGo.MinimalAdvertisement,
                        is ClassifiedPeripheral.ProteGo.PotentialAdvertisement -> it.syncBeaconIds()
                    }
                        .concatWith(Observable.just(SyncEvent.Process.End.Finished))
                        .startWithArray(SyncEvent.Process.Start)
                }
            }
            .defaultIfEmpty(SyncEvent.Process.End.Aborted)
            .startWithArray(SyncEvent.Connection.Connecting)
            .takeUntil { it is SyncEvent.Process.End }
            .onErrorReturn {
                when (it) {
                    is BleGattException -> SyncEvent.Connection.Error.Gatt(it.status)
                    is BleAdapterDisabledException -> SyncEvent.Connection.Error.AdapterOff
                    else -> throw it
                }
            }

    private fun DiscoveryProcess.Finished.Found.writeLocalBeaconIdOnly(): Observable<SyncEvent.Process> =
        this.writeLocalBeaconId().toObservable().cast(SyncEvent.Process::class.java)

    private fun DiscoveryProcess.Finished.Found.syncBeaconIds(): Observable<SyncEvent.Process> =
        this.readRemoteBeaconId()
            .toObservable()
            .publish { readRemoteBeaconIdObs ->
                val writeLocalBeaconIdIfReadSuccessfulObs = readRemoteBeaconIdObs
                    .filter { it is SyncEvent.Process.ReadBeaconId.Valid }
                    .flatMapSingle { writeLocalBeaconId() }
                Observable.concatArray(
                    readRemoteBeaconIdObs,
                    writeLocalBeaconIdIfReadSuccessfulObs
                )
            }

    private fun DiscoveryProcess.Finished.Found.writeLocalBeaconId(): Single<SyncEvent.Process.WrittenBeaconId> =
        this@ProteGoConnector.beaconIdToUseSubject
            .take(1)
            .singleOrError()
            .flatMap { currentBeaconIdToUse ->
                when (currentBeaconIdToUse) {
                    BeaconIdToUse.NoBeaconId ->
                        Single.just(SyncEvent.Process.WrittenBeaconId.Invalid)
                    is BeaconIdToUse.CurrentBeaconId ->
                        this.connection.writeCharacteristic(
                            this.proteGoCharacteristic,
                            currentBeaconIdToUse.beaconId.byteArray
                        )
                            .map<SyncEvent.Process.WrittenBeaconId> { SyncEvent.Process.WrittenBeaconId.Success }
                            .onErrorReturn {
                                if (it is BleGattCharacteristicException) SyncEvent.Process.WrittenBeaconId.Failure(it.status)
                                else throw it
                            }
                }
            }

    @Suppress("RemoveExplicitTypeArguments") // does not infer well
    private fun DiscoveryProcess.Finished.Found.readRemoteBeaconId(): Single<SyncEvent.Process.ReadBeaconId> =
        Single.zip<ByteArray, Int, SyncEvent.Process.ReadBeaconId>(
            this.connection.readCharacteristic(this.proteGoCharacteristic),
            this.connection.readRssi(),
            BiFunction { bytes, rssi ->
                if (bytes.size == BeaconId.byteCount) {
                    val beaconId = BeaconId(bytes, 0)
                    val remoteBeaconId = BeaconIdRemote(beaconId, rssi)
                    SyncEvent.Process.ReadBeaconId.Valid(remoteBeaconId)
                } else {
                    SyncEvent.Process.ReadBeaconId.Invalid
                }
            }
        )
            .onErrorReturn {
                if (it is BleGattCharacteristicException) SyncEvent.Process.ReadBeaconId.Failure(it.status)
                else throw it
            }
}