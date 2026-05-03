# SoterIA - Enterprise Intelligent Emergency Management System

## Project Overview

SoterIA is a sophisticated, native Java-based emergency management platform designed to orchestrate critical responses in high-pressure environments. By integrating natural language processing, deterministic classification logic, and a multi-layered architectural design, SoterIA provides a robust framework for detecting, managing, and documenting emergency events.

The system is engineered for maximum reliability, utilizing the latest features of JDK 25 to provide a high-performance desktop experience through a responsive JavaFX interface. SoterIA serves as both a primary interaction point for users in crisis and a coordination hub for emergency dispatch protocols.

## Technical Architecture and Design Principles

SoterIA follows a strict implementation of **Clean Architecture** and **SOLID principles**, ensuring a complete separation between domain logic and external infrastructures.

### 1. Core Model (Immutable Domain)
The foundation of the system is built on **Java Records**, ensuring that emergency data remains immutable and thread-safe throughout the application lifecycle.
- **UserData**: Secure storage of user profiles, medical history, and emergency contacts.
- **EmergencyEvent**: A comprehensive record of a detected crisis, including classification, timestamp, location data, and severity levels.

### 2. Interface Layer (Dependency Inversion)
Critical infrastructure services are consumed through interfaces so backends can be swapped without touching callers:
- **AlertService**: Decouples the UI from the underlying notification mechanism.
- **LocationProvider**: Abstraction for geographic positioning.

### 3. Intelligence Layer (RAG Pipeline)
Offline reasoning is delivered by three coordinated components under `com.soteria.infrastructure.intelligence`:
- **MedicalKnowledgeBase**: Lucene BM25 retrieval + JGraphT relationship graph over `medical_protocols.json`.
- **LocalBrainService**: GGUF LLM inference via the **MirceaMihaiBontoi/java-llama.cpp** fork (JNI + Maven Java API `de.kherud:llama`; native from `lib/` or `vendor/java-llama.cpp` build — see `lib/GEMMA4_JLLAMA.txt`).
- **SherpaSTTService**: Offline speech-to-text via **Sherpa-ONNX** (Whisper Small ASR + Silero VAD), same stack for every LLM profile.

### 4. Infrastructure Layer (System Implementation)
Handles the technical details of the environment:
- **Persistence**: JSON-based storage for the single device-owner profile (`ProfileRepository` → `~/.soteria/profile.json`).
- **Notification**: Local alert log + simulated emergency-service dispatch (`NotificationAlertService`). Real SMS/call integration is a later phase.
- **Sensor**: System GPS wrapper (`SystemGPSLocation`) and best-effort device phone number read (`DevicePhoneDetector`).
- **Bootstrap**: `BootstrapService` orchestrates background downloads, engine loading and a silent LLM warmup turn so the KV cache holds the system prompt before the user's first real message. Exposes observable `statusProperty` / `progressProperty` / `readyProperty` for direct FXML binding.

### 5. UI Layer (Reactive Interface)
JavaFX + **AtlantaFX PrimerDark** theme with a thin overrides stylesheet (`main.css`). No login screen — SoterIA is single-user by design:
- **Onboarding wizard** (`OnboardingController` + `onboarding-view.fxml`): step 1 picks the on-device Gemma 4 profile (RAM-based *Recommended* tag and weight in GB) and language (pre-selected from GPS/locale, English fallback). Step 2 captures the emergency profile. An installation overlay covers the gap if downloads aren't finished when the user submits.
- **Chat screen** (`ChatController` + `chat-view.fxml`): conversation with text + voice input, rendered as custom bubbles on top of the PrimerDark base.
- **Asynchronous Execution**: All I/O and LLM/STT calls run on worker threads; the FX thread only touches the scene graph.

## Key Features and Capabilities

### 🧠 Hardware-Aware Native Intelligence (SoterIA 2.0)
SoterIA 2.0 picks among **three LLM profiles** from installed RAM (`SystemCapability`). All LLMs ship as **GGUF** bundles. **STT is not tied to the profile**: one shared **Sherpa-ONNX** Whisper Small model is provisioned for every configuration.

#### Three LLM profiles (GGUF via llama.cpp)
RAM thresholds in code: **&lt; 6 GB** → Lite; **6–12 GB** → Balanced; **≥ 12 GB** → Expert (defaults; the user can override in onboarding).

| Profile (enum) | Bundle role | Typical GGUF |
|----------------|-------------|--------------|
| **Lite** | Smallest weights | Gemma 4 **E2B** Q4 — `unsloth/gemma-4-E2B-it-GGUF` |
| **Balanced** | Default mid tier | Gemma 4 **E4B** Q4 — `unsloth/gemma-4-E4B-it-GGUF` |
| **Expert** | Highest quality | Gemma 4 **E4B** Q8 — same repo slug, heavier quant |

**STT/TTS**: **Sherpa-ONNX** end to end — `sherpa-onnx-whisper-small` for STT; **Kokoro** (`kokoro-multi-lang-v1_0`) for TTS via `SherpaTTSService`.

> **Gemma 4 & JNI**: use a `jllama` built from **[MirceaMihaiBontoi/java-llama.cpp](https://github.com/MirceaMihaiBontoi/java-llama.cpp)** (same tree as `vendor/java-llama.cpp`) and place it under `lib/` (see `lib/GEMMA4_JLLAMA.txt`). The Java entrypoint remains `de.kherud.llama`; `LlamaNativeBootstrap` selects `lib/` when appropriate.

### 🏥 100% Offline RAG Pipeline
Survival-critical reliability via a local **Retrieval-Augmented Generation** pipeline:
- **Apache Lucene (BM25)**: In-memory text index over medical protocols (`title` + `keywords`), ranked by relevance.
- **JGraphT**: Undirected knowledge graph linking protocols by shared category or cross-references in their steps. The top Lucene hit is enriched with its graph neighbors before being passed to the LLM.
- **llama.cpp (Java bindings)**: JNI + GGUF via the fork above; `de.kherud:llama` carries the Java API, **`jllama` must match the fork build** in `lib/` (see `lib/GEMMA4_JLLAMA.txt`).

### 🌍 English-Core Multilingual Strategy
Using an "English-Core" reasoning approach for maximum precision:
- **Cross-Lingual Reasoning**: The LLM reads English protocols and generates instructions in the user's selected language (e.g., Spanish).
- **Semantic Mapping**: Multilingual keyword indexing allows users to trigger English protocols using native terminology.

### Protocol Catalogue
`medical_protocols.json` currently ships five core protocols (extensible without code changes):
- **Vital**: CPR, Severe External Hemorrhage.
- **Airway**: Choking (5-and-5 / Heimlich).
- **Trauma**: Thermal Burns.
- **Neurological**: Stroke (FAST assessment).

### Multimodal Input Handling
- **Conversational Synthesis**: A guided UX that asks the right questions based on the detected emergency type.
- **Voice-to-Action**: Real-time offline speech through **Sherpa-ONNX** (mic → VAD → Whisper) for hands-free reporting.

### Operational Logging
Every dispatched alert is appended to `logs/emergency_alerts.log` as a plain-text audit trail. The file is created lazily on first alert.

## Project Structure

```text
com.soteria.core
├── model              # Immutable records (UserData, EmergencyEvent)
└── interfaces         # Service contracts (AlertService, LocationProvider)

com.soteria.infrastructure
├── bootstrap          # BootstrapService (background downloads, engine warmup)
├── intelligence       # STT, LLM, knowledge base, model provisioning
├── notification       # NotificationAlertService (log + simulated call)
├── persistence        # ProfileRepository (device-owner profile.json)
└── sensor             # SystemGPSLocation, DevicePhoneDetector

com.soteria.ui
├── controller         # OnboardingController, ChatController
└── MainApp            # JavaFX entry point (AtlantaFX PrimerDark)
```

## System Implementation Standards

### OOP & Language
- **Records**: Immutable transport for `UserData`, `EmergencyEvent`, `ChatMessage`.
- **Interfaces + DI**: `AlertService` and `LocationProvider` decouple UI from infrastructure.
- **Java 25 target**: `<release>25</release>` in `pom.xml`.

## Setup and Installation

### System Requirements
- **Java Runtime**: JDK 25 or higher.
- **Build Tool**: Apache Maven 3.9.x.
- **Desktop**: Windows, macOS, Linux. **LLM**: Gemma 4 E4B GGUF (`unsloth/gemma-4-E4B-it-GGUF`); use a recent `jllama` native (`lib/`, see `lib/GEMMA4_JLLAMA.txt`).
- **Android**: GluonFX build pipeline + `libllama.so` compiled for `arm64-v8a` (Vulkan backend recommended).
- **iOS**: planned as a separate native Swift app leveraging CoreML + llama.cpp Swift bindings; shares `medical_protocols.json` and data contracts with the Java core.

### Build Instructions
```bash
# Clean previous builds and compile sources
mvn clean compile

# Execute the application
mvn javafx:run
```

### Testing and Verification
The system includes an extensive suite of unit tests to verify domain logic and record integrity:
```bash
mvn test
```

## Licensing and Governance
This project is developed under high-standard professional guidelines for emergency systems.

**Lead Developer**: Mircea Mihai Bontoi