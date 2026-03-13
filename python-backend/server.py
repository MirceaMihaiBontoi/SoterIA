import csv
import joblib
from difflib import get_close_matches
from pathlib import Path
from fastapi import FastAPI
from pydantic import BaseModel
from spellchecker import SpellChecker

MODEL_PATH = Path(__file__).parent / "models" / "emergency_classifier.pkl"
DATA_PATH = Path(__file__).parent / "data" / "emergencies_dataset.csv"
DATA_PATH = Path(__file__).parent / "data" / "emergencies_dataset.csv"

app = FastAPI(title="Emergency Classifier API")

model = joblib.load(MODEL_PATH)
spell = SpellChecker(language="es")

# Extraer palabras del dataset como vocabulario principal de correccion
with open(DATA_PATH, encoding="utf-8") as f:
    reader = csv.DictReader(f)
    dataset_words = set()
    for row in reader:
        for word in row["text"].lower().split():
            if len(word) > 2:
                dataset_words.add(word)
VOCABULARY = list(dataset_words)
spell.word_frequency.load_words(VOCABULARY)

PRIORITY_MAP = {
    "MEDICAL": 9,
    "FIRE": 8,
    "TRAFFIC": 7,
    "SECURITY": 8,
    "NATURAL": 7,
}

INSTRUCTIONS_MAP = {
    "MEDICAL": [
        "Mantenga la calma y no mueva al paciente",
        "Compruebe si respira y tiene pulso",
        "Si no respira, inicie RCP si sabe hacerlo",
        "Mantenga al paciente abrigado y comodo",
        "No le de comida ni bebida",
    ],
    "FIRE": [
        "Evacue la zona inmediatamente",
        "No use ascensores, use las escaleras",
        "Cubra su boca con un pano humedo",
        "Cierre puertas y ventanas al salir",
        "No regrese al edificio por ningun motivo",
    ],
    "TRAFFIC": [
        "No mueva a los heridos salvo peligro inminente",
        "Senalice el accidente con triangulos de emergencia",
        "Apague el motor de los vehiculos implicados",
        "Si hay derrame de combustible, alejese del vehiculo",
        "Preste atencion a posibles lesiones de columna",
    ],
    "SECURITY": [
        "Alejese de la zona de peligro",
        "No confronte al agresor",
        "Busque refugio en un lugar seguro",
        "Intente recordar la descripcion del agresor",
        "No toque nada que pueda ser evidencia",
    ],
    "NATURAL": [
        "Busque refugio en una estructura solida",
        "Alejese de ventanas, arboles y postes electricos",
        "Si hay inundacion, vaya a zonas altas",
        "No cruce rios o zonas inundadas a pie ni en coche",
        "Tenga preparado un kit de emergencia",
    ],
}

LABEL_NAMES = {
    "MEDICAL": "Emergencia Medica",
    "FIRE": "Incendio",
    "TRAFFIC": "Accidente de Trafico",
    "SECURITY": "Emergencia de Seguridad",
    "NATURAL": "Desastre Natural",
}


class ClassifyRequest(BaseModel):
    text: str


class ClassifyResponse(BaseModel):
    type: str
    type_name: str
    priority: int
    confidence: float
    corrected_text: str
    instructions: list[str]


def correct_text(text: str) -> str:
    words = text.lower().split()
    corrected = []
    for word in words:
        if len(word) <= 2 or word in VOCABULARY:
            corrected.append(word)
            continue
        # Paso 1: si es palabra valida en español, no tocar
        if word in spell:
            corrected.append(word)
            continue
        # Paso 2: buscar la mas parecida en el CSV (prioridad sobre pyspellchecker)
        matches = get_close_matches(word, VOCABULARY, n=1, cutoff=0.7)
        if matches:
            corrected.append(matches[0])
            continue
        # Paso 3: fallback a pyspellchecker para palabras que no matchean el CSV
        correction = spell.correction(word)
        corrected.append(correction if correction else word)
    return " ".join(corrected)


@app.post("/classify", response_model=ClassifyResponse)
def classify(request: ClassifyRequest):
    corrected = correct_text(request.text)

    label = model.predict([corrected])[0]

    probabilities = model.predict_proba([corrected])[0]
    confidence = max(probabilities)

    return ClassifyResponse(
        type=label,
        type_name=LABEL_NAMES[label],
        priority=PRIORITY_MAP[label],
        confidence=round(confidence, 2),
        corrected_text=corrected,
        instructions=INSTRUCTIONS_MAP[label],
    )


@app.get("/health")
def health():
    return {"status": "ok"}
