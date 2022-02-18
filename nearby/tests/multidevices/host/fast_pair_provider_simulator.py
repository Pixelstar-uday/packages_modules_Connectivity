"""Fast Pair provider simulator role."""

from mobly import asserts
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import snippet_event
import retry

import event_helper

# The package name of the provider simulator snippet.
FP_PROVIDER_SIMULATOR_SNIPPETS_PACKAGE = 'android.nearby.multidevices'

# Events reported from the provider simulator snippet.
ON_SCAN_MODE_CHANGE_EVENT = 'onScanModeChange'
ON_ADVERTISING_CHANGE_EVENT = 'onAdvertisingChange'

# Target scan mode.
DISCOVERABLE_MODE = 'DISCOVERABLE'

# Abbreviations for common use type.
AndroidDevice = android_device.AndroidDevice
SnippetEvent = snippet_event.SnippetEvent
wait_for_event = event_helper.wait_callback_event


class FastPairProviderSimulator:
    """A proxy for provider simulator snippet on the device."""

    def __init__(self, ad: AndroidDevice) -> None:
        self._ad = ad
        self._provider_status_callback = None

    def load_snippet(self) -> None:
        """Starts the provider simulator snippet and connects.

        Raises:
          SnippetError: Illegal load operations are attempted.
        """
        self._ad.load_snippet(
            name='fp', package=FP_PROVIDER_SIMULATOR_SNIPPETS_PACKAGE)

    def start_provider_simulator(self, model_id: str,
                                 anti_spoofing_key: str) -> None:
        """Starts the Fast Pair provider simulator.

        Args:
          model_id: A 3-byte hex string for seeker side to recognize the device (ex:
            0x00000C).
          anti_spoofing_key: A public key for registered headsets.
        """
        self._provider_status_callback = self._ad.fp.startProviderSimulator(
            model_id, anti_spoofing_key)

    def stop_provider_simulator(self) -> None:
        """Stops the Fast Pair provider simulator."""
        self._ad.fp.stopProviderSimulator()

    @retry.retry(tries=3)
    def get_ble_mac_address(self) -> str:
        """Gets Bluetooth low energy mac address of the provider simulator.

        The BLE mac address will be set by the AdvertisingSet.getOwnAddress()
        callback. This is the callback flow in the custom Android build. It takes
        a while after advertising started so we use retry here to wait it.

        Returns:
          The BLE mac address of the Fast Pair provider simulator.
        """
        return self._ad.fp.getBluetoothLeAddress()

    def wait_for_discoverable_mode(self, timeout_seconds: int) -> None:
        """Waits onScanModeChange event to ensure provider is discoverable.

        Args:
          timeout_seconds: The number of seconds to wait before giving up.
        """

        def _on_scan_mode_change_event_received(
                scan_mode_change_event: SnippetEvent, elapsed_time: int) -> bool:
            scan_mode = scan_mode_change_event.data['mode']
            self._ad.log.info(
                'Provider simulator changed the scan mode to %s in %d seconds.',
                scan_mode, elapsed_time)
            return scan_mode == DISCOVERABLE_MODE

        def _on_scan_mode_change_event_waiting(elapsed_time: int) -> None:
            self._ad.log.info(
                'Still waiting "%s" event callback from provider side '
                'after %d seconds...', ON_SCAN_MODE_CHANGE_EVENT, elapsed_time)

        def _on_scan_mode_change_event_missed() -> None:
            asserts.fail(f'Timed out after {timeout_seconds} seconds waiting for '
                         f'the specific "{ON_SCAN_MODE_CHANGE_EVENT}" event.')

        wait_for_event(
            callback_event_handler=self._provider_status_callback,
            event_name=ON_SCAN_MODE_CHANGE_EVENT,
            timeout_seconds=timeout_seconds,
            on_received=_on_scan_mode_change_event_received,
            on_waiting=_on_scan_mode_change_event_waiting,
            on_missed=_on_scan_mode_change_event_missed)

    def wait_for_advertising_start(self, timeout_seconds: int) -> None:
        """Waits onAdvertisingChange event to ensure provider is advertising.

        Args:
          timeout_seconds: The number of seconds to wait before giving up.
        """

        def _on_advertising_mode_change_event_received(
                scan_mode_change_event: SnippetEvent, elapsed_time: int) -> bool:
            advertising_mode = scan_mode_change_event.data['isAdvertising']
            self._ad.log.info(
                'Provider simulator changed the advertising mode to %s in %d seconds.',
                advertising_mode, elapsed_time)
            return advertising_mode

        def _on_advertising_mode_change_event_waiting(elapsed_time: int) -> None:
            self._ad.log.info(
                'Still waiting "%s" event callback from provider side '
                'after %d seconds...', ON_ADVERTISING_CHANGE_EVENT, elapsed_time)

        def _on_advertising_mode_change_event_missed() -> None:
            asserts.fail(f'Timed out after {timeout_seconds} seconds waiting for '
                         f'the specific "{ON_ADVERTISING_CHANGE_EVENT}" event.')

        wait_for_event(
            callback_event_handler=self._provider_status_callback,
            event_name=ON_ADVERTISING_CHANGE_EVENT,
            timeout_seconds=timeout_seconds,
            on_received=_on_advertising_mode_change_event_received,
            on_waiting=_on_advertising_mode_change_event_waiting,
            on_missed=_on_advertising_mode_change_event_missed)
