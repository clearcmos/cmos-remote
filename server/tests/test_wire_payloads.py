"""The server half of the response-payload contract.

spec/wire-payloads.json is the shared shape; the Android suite decodes the same
file into its data classes. Renaming a field here without renaming it there
would otherwise leave the app quietly showing default values.
"""

import json
import pathlib

import pytest

import main

PAYLOADS = json.loads(
    (pathlib.Path(__file__).resolve().parents[2] / "spec" / "wire-payloads.json").read_text()
)


def test_status_model_matches_the_contract():
    payload = PAYLOADS["status_connected"]
    model = main.StatusResponse(
        muted=False, volume=74, bluetooth_on=True, bluetooth_connected="Soundcore Life Q30"
    )
    assert model.model_dump() == payload


def test_status_model_serializes_a_missing_device_as_null():
    payload = PAYLOADS["status_bluetooth_off"]
    model = main.StatusResponse(muted=True, volume=0, bluetooth_on=False)
    assert model.model_dump() == payload


@pytest.mark.parametrize("key", ["action_muted", "action_without_state"])
def test_action_model_matches_the_contract(key):
    payload = PAYLOADS[key]
    model = main.ActionResponse(**payload)
    assert model.model_dump() == payload


def test_volume_model_matches_the_contract():
    payload = PAYLOADS["volume_set"]
    assert main.VolumeResponse(**payload).model_dump() == payload


def test_health_endpoint_matches_the_contract(open_client):
    assert open_client.get("/health").json() == PAYLOADS["health"]


def test_status_endpoint_matches_the_contract(open_client):
    # The stubbed commands in conftest report exactly the connected fixture.
    assert open_client.get("/status").json() == PAYLOADS["status_connected"]
