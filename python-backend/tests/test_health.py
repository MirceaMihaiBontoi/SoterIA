"""
Tests de humo sobre el endpoint /health.
Si esto falla es que la app ni siquiera arranca.
"""
from fastapi.testclient import TestClient

import server


client = TestClient(server.app)


def test_health_ok():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_system_info_has_expected_keys():
    resp = client.get("/system-info")
    assert resp.status_code == 200
    body = resp.json()
    for key in ("ram_gb", "has_gpu", "stt_model", "tts_model", "llm_primary"):
        assert key in body
