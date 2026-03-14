import csv
import joblib
from difflib import get_close_matches
from pathlib import Path
from fastapi import FastAPI
from pydantic import BaseModel
from spellchecker import SpellChecker

MODEL_PATH = Path(__file__).parent / "models" / "emergency_classifier.pkl"
DATA_PATH = Path(__file__).parent / "data" / "emergencies_dataset.csv"
CONFIG_PATH = Path(__file__).parent / "data" / "emergency_config.json"

app = FastAPI(title="Emergency Classifier API")

model = joblib.load(MODEL_PATH)
spell = SpellChecker(language="es")

# Cargar configuracion desde JSON externo
import json
with open(CONFIG_PATH, encoding="utf-8") as f:
    config = json.load(f)

PRIORITY_MAP = config["priority_map"]
LABEL_NAMES = config["label_names"]
CONTEXTUAL_INSTRUCTIONS = config["contextual_instructions"]
CONTEXT_DESCRIPTIONS = config["context_descriptions"]

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


def get_instructions(label: str, text: str) -> list[str]:
    category = CONTEXTUAL_INSTRUCTIONS.get(label, {})
    text_lower = text.lower()
    for keywords, instructions in category.items():
        if keywords == "_default":
            continue
        if any(kw in text_lower for kw in keywords.split("|")):
            return instructions
    return category.get("_default", ["Mantenga la calma y llame al 112"])


def get_context(label: str, text: str) -> str:
    contexts = CONTEXT_DESCRIPTIONS.get(label, {})
    text_lower = text.lower()
    for keywords, desc in contexts.items():
        if keywords == "_default":
            continue
        if any(kw in text_lower for kw in keywords.split("|")):
            return desc
    return contexts.get("_default", "una emergencia")


class ClassifyRequest(BaseModel):
    text: str


class EmergencyDetail(BaseModel):
    type: str
    type_name: str
    confidence: float
    context: str
    instructions: list[str]


class ClassifyResponse(BaseModel):
    priority: int
    corrected_text: str
    emergencies: list[EmergencyDetail]


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


SECONDARY_THRESHOLD = 0.20


def has_relevant_keywords(label: str, text: str) -> bool:
    """Comprueba si el texto contiene keywords relevantes para esa categoria."""
    # Revisar keywords en instrucciones contextuales
    for keywords in CONTEXTUAL_INSTRUCTIONS.get(label, {}):
        if keywords == "_default":
            continue
        if any(kw in text.lower() for kw in keywords.split("|")):
            return True
    # Revisar keywords en descripciones de contexto
    for keywords in CONTEXT_DESCRIPTIONS.get(label, {}):
        if keywords == "_default":
            continue
        if any(kw in text.lower() for kw in keywords.split("|")):
            return True
    return False


@app.post("/classify", response_model=ClassifyResponse)
def classify(request: ClassifyRequest):
    corrected = correct_text(request.text)

    label = model.predict([corrected])[0]
    probabilities = model.predict_proba([corrected])[0]
    classes = model.classes_

    # Ordenar por probabilidad descendente
    ranked = sorted(zip(classes, probabilities), key=lambda x: -x[1])

    primary_label, primary_conf = ranked[0]
    primary = EmergencyDetail(
        type=primary_label,
        type_name=LABEL_NAMES[primary_label],
        confidence=round(primary_conf, 2),
        context=get_context(primary_label, corrected),
        instructions=get_instructions(primary_label, corrected),
    )

    emergencies = [primary]

    # Incluir secundaria solo si supera umbral Y tiene keywords relevantes en el texto
    secondary_label, secondary_conf = ranked[1]
    if secondary_conf >= SECONDARY_THRESHOLD and has_relevant_keywords(secondary_label, corrected):
        emergencies.append(EmergencyDetail(
            type=secondary_label,
            type_name=LABEL_NAMES[secondary_label],
            confidence=round(secondary_conf, 2),
            context=get_context(secondary_label, corrected),
            instructions=get_instructions(secondary_label, corrected),
        ))

    # Prioridad maxima entre las emergencias detectadas
    max_priority = max(PRIORITY_MAP[e.type] for e in emergencies)

    return ClassifyResponse(
        priority=max_priority,
        corrected_text=corrected,
        emergencies=emergencies,
    )


@app.get("/geolocate")
def geolocate():
    """Obtiene ubicacion aproximada del usuario por IP publica."""
    import httpx
    try:
        r = httpx.get("http://ip-api.com/json/?lang=es&fields=status,city,regionName,country,lat,lon,query", timeout=5)
        data = r.json()
        if data.get("status") == "success":
            return {
                "city": data.get("city", ""),
                "region": data.get("regionName", ""),
                "country": data.get("country", ""),
                "lat": data.get("lat", 0),
                "lon": data.get("lon", 0),
                "ip": data.get("query", ""),
            }
        return {"error": "No se pudo determinar la ubicacion"}
    except Exception as e:
        return {"error": str(e)}


@app.get("/health")
def health():
    return {"status": "ok"}
