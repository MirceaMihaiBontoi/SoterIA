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
- **LocalBrainService**: GGUF LLM inference via `de.kherud:llama` (llama.cpp JNI).
- **VoskSTTService**: Offline speech-to-text with hardware-appropriate model size.

### 4. Infrastructure Layer (System Implementation)
Handles the technical details of the environment:
- **Persistence**: JSON-based storage for user sessions (`JsonUserPersistence`).
- **Notification**: Local alert log + simulated emergency-service dispatch (`NotificationAlertService`). Real SMS/call integration is a later phase.
- **Sensor**: System GPS wrapper (`SystemGPSLocation`).

### 5. UI Layer (Reactive Interface)
A state-of-the-art JavaFX implementation using the Model-View-Controller (MVC) pattern:
- **Asynchronous Execution**: All network and heavy logic operations are offloaded from the Application Thread to prevent UI freezing during critical moments.
- **CSS-Driven Design**: A professional, high-contrast theme optimized for readability in emergency contexts.

## Key Features and Capabilities

### 🧠 Hardware-Aware Native Intelligence (SoterIA 2.0)
SoterIA 2.0 introduces a state-of-the-art **Tiered Scaling Architecture** that dynamically provisions AI resources based on the device's physical hardware. All LLMs are shipped as **GGUF** single-file bundles for reproducible, dependency-free deployment.

#### RAM-Based 5-Tier Scaling (GGUF via llama.cpp):
- **Ultra-Lite (< 3GB)**: Gemma 3 1B-it Q4_K_M + Vosk Small.
- **Lite (3 - 4GB)**: Gemma 3 1B-it Q8_0 + Vosk Small.
- **Balanced (4 - 6GB)**: Gemma 3 4B-it Q4_K_M + Vosk Standard.
- **Performance (6 - 12GB)**: Gemma 3 4B-it Q4_K_M + Vosk Standard.
- **Ultra (≥ 12GB)**: Gemma 3 4B-it Q8_0 + Vosk Standard.

> **Roadmap**: upgrade a Gemma 4 (E2B/E4B) pendiente de `de.kherud:llama` >= 4.3.0 con soporte nativo para la arquitectura `gemma4`.

### 🏥 100% Offline RAG Pipeline
Survival-critical reliability via a local **Retrieval-Augmented Generation** pipeline:
- **Apache Lucene (BM25)**: In-memory text index over medical protocols (`title` + `keywords`), ranked by relevance.
- **JGraphT**: Undirected knowledge graph linking protocols by shared category or cross-references in their steps. The top Lucene hit is enriched with its graph neighbors before being passed to the LLM.
- **llama.cpp (via `de.kherud:llama`)**: Native execution of GGUF LLMs with CPU, Metal (macOS/iOS) and Vulkan (Android) backends.

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
- **Voice-to-Action**: Real-time native voice processing (Vosk) allows hands-free reporting.

### Operational Logging
Every dispatched alert is appended to `logs/emergency_alerts.log` as a plain-text audit trail. The file is created lazily on first alert.

## Project Structure

```text
com.soteria.core
├── model              # Immutable records (UserData, EmergencyEvent)
└── interfaces         # Service contracts (AlertService, LocationProvider)

com.soteria.infrastructure
├── intelligence       # STT, LLM, knowledge base, model provisioning
├── notification       # NotificationAlertService (log + simulated call)
├── persistence        # JsonUserPersistence (session + user profile)
└── sensor             # SystemGPSLocation

com.soteria.ui
├── controller         # ChatController, LoginController
└── MainApp            # JavaFX entry point
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
- **Desktop**: Windows, macOS, Linux (native llama.cpp binaries bundled via `de.kherud:llama`).
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