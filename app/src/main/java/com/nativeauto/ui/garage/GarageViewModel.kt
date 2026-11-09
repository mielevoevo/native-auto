package com.nativeauto.ui.garage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.garage.BikeDraft
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.FuelPrice
import com.nativeauto.garage.FuelState
import com.nativeauto.garage.RefuelDraft
import com.nativeauto.garage.ServiceDraft
import com.nativeauto.garage.ServiceDue
import com.nativeauto.garage.ServiceRecord
import com.nativeauto.garage.GaragePrompt
import com.nativeauto.garage.GarageRepository
import com.nativeauto.garage.OdometerReading
import com.nativeauto.garage.OdometerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the Garage screen, the settings row and the Garage prompts (one per activity). */
@HiltViewModel
class GarageViewModel @Inject constructor(private val garage: GarageRepository) : ViewModel() {

    /** Null while loading, so the empty state doesn't flash. */
    val bikes: StateFlow<List<BikeRecord>?> = garage.bikes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val active: StateFlow<BikeRecord?> = garage.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val prompt: StateFlow<GaragePrompt?> = garage.prompt

    fun odometer(bikeId: String): Flow<OdometerState> = garage.odometer(bikeId)

    fun readings(bikeId: String): Flow<List<OdometerReading>> = garage.readings(bikeId)

    /** [onAdded] gets the new bike's id, so the screen can select it. */
    fun add(draft: BikeDraft, odometerKm: Double?, onAdded: (String) -> Unit) = viewModelScope.launch {
        onAdded(garage.addBike(draft, odometerKm))
    }

    fun update(id: String, draft: BikeDraft) = viewModelScope.launch { garage.updateBike(id, draft) }

    fun ride(id: String) = viewModelScope.launch { garage.activate(id) }

    fun enterReading(id: String, km: Double) = viewModelScope.launch { garage.enterReading(id, km) }

    // ---- 11b ----

    fun fuel(bikeId: String): Flow<FuelState> = garage.fuel(bikeId)

    val prices: StateFlow<List<FuelPrice>> = garage.prices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logRefuel(bikeId: String, draft: RefuelDraft) = viewModelScope.launch { garage.logRefuel(bikeId, draft) }

    fun addUntrackedKm(bikeId: String, km: Double) = viewModelScope.launch { garage.addUntrackedKm(bikeId, km) }

    // ---- 11c ----

    fun services(bikeId: String): Flow<List<ServiceDue>> = garage.services(bikeId)

    fun serviceHistory(bikeId: String): Flow<List<ServiceRecord>> = garage.serviceHistory(bikeId)

    fun addService(bikeId: String, draft: ServiceDraft) = viewModelScope.launch { garage.addService(bikeId, draft) }

    fun updateService(id: Long, draft: ServiceDraft) = viewModelScope.launch { garage.updateService(id, draft) }

    fun deleteService(id: Long) = viewModelScope.launch { garage.deleteService(id) }

    fun markServiceDone(id: Long, odometerKm: Double?, costRp: Double?, note: String?) =
        viewModelScope.launch { garage.markServiceDone(id, odometerKm, costRp, note) }

    private val _refuelArmed = MutableStateFlow<Long?>(null)

    /** The fill-up whose delete was tapped once (3 s to confirm). */
    val refuelArmed: StateFlow<Long?> = _refuelArmed.asStateFlow()
    private var refuelDisarm: Job? = null

    /** Two taps within 3 s. Returns true when it deleted. */
    fun onDeleteRefuelTapped(id: Long): Boolean {
        refuelDisarm?.cancel()
        if (_refuelArmed.value == id) {
            _refuelArmed.value = null
            viewModelScope.launch { garage.deleteRefuel(id) }
            return true
        }
        _refuelArmed.value = id
        refuelDisarm = viewModelScope.launch {
            delay(3_000)
            _refuelArmed.value = null
        }
        return false
    }

    fun answerOdometerPrompt(km: Double?) = viewModelScope.launch { garage.answerOdometerPrompt(km) }

    fun answerWhichBike(id: String) = viewModelScope.launch { garage.answerWhichBike(id) }

    fun dismissPrompt() = garage.dismissPrompt()

    private val _deleteArmed = MutableStateFlow<String?>(null)

    /** The bike whose Delete was tapped once (3 s to confirm). */
    val deleteArmed: StateFlow<String?> = _deleteArmed.asStateFlow()
    private var disarm: Job? = null

    /** Two taps within 3 s, like Delete trip. Returns true when it deleted. */
    fun onDeleteTapped(id: String): Boolean {
        disarm?.cancel()
        if (_deleteArmed.value == id) {
            _deleteArmed.value = null
            viewModelScope.launch { garage.deleteBike(id) }
            return true
        }
        _deleteArmed.value = id
        disarm = viewModelScope.launch {
            delay(3_000)
            _deleteArmed.value = null
        }
        return false
    }
}
