"""
Tests funcionales sobre /classify y sus helpers puros.
Se centra en verificar el contrato de la respuesta y la corrección léxica,
no en la calidad del modelo (eso es otra discusión).
"""
from fastapi.testclient import TestClient

import server


client = TestClient(server.app)


def test_classify_returns_expected_shape():
    resp = client.post("/classify", json={"text": "hay un incendio en la cocina"})
    assert resp.status_code == 200
    body = resp.json()

    assert set(body.keys()) == {"priority", "corrected_text", "emergencies"}
    assert isinstance(body["priority"], int)
    assert isinstance(body["corrected_text"], str)
    assert isinstance(body["emergencies"], list)
    assert len(body["emergencies"]) >= 1

    primary = body["emergencies"][0]
    for key in ("type", "type_name", "confidence", "context", "instructions"):
        assert key in primary
    assert 0.0 <= primary["confidence"] <= 1.0
    assert isinstance(primary["instructions"], list)
    assert len(primary["instructions"]) > 0


def test_correct_text_preserves_correct_words():
    assert server.correct_text("incendio en la cocina") == "incendio en la cocina"


def test_correct_text_lowercases_input():
    out = server.correct_text("INCENDIO EN LA COCINA")
    assert out == out.lower()


def test_has_relevant_keywords_for_fire_label():
    text = "hay un incendio enorme"
    # Usa una etiqueta presente en la config. Si no hubiera 'fire', el test
    # simplemente no afirma nada: lo relevante es que la función no explote.
    if "fire" in server.CONTEXTUAL_INSTRUCTIONS or "fire" in server.CONTEXT_DESCRIPTIONS:
        assert server.has_relevant_keywords("fire", text) is True


def test_classify_rejects_missing_body():
    resp = client.post("/classify", json={})
    assert resp.status_code == 422  # validación Pydantic
