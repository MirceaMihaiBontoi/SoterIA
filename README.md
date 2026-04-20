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
To ensure the system is future-proof and testable, all critical infrastructure services are consumed through interfaces:
- **AlertService**: Decouples the UI from the underlying notification mechanisms (Visual, Network, or Protocol-based).
- **IntelligenceProvider**: Allows the seamless swapping of classification engines (e.g., transition from Remote HTTP AI to Local GGUF models via llama.cpp).
- **LocationProvider**: Abstractions for geographic positioning and coordinate resolution.

### 3. Logic Layer (Orchestration Engine)
The `EmergencyDetector` acts as the system's brain, processing raw inputs through a hierarchical detection pipeline:
- **Primary Engine**: Neural-based intent recognition via integrated intelligence services.
- **Fallback Engine**: Deterministic pattern matching using a proprietary keyword and act-protocol library, ensuring 100% availability even during network failures.

### 4. Infrastructure Layer (System Implementation)
Handles the technical details of the environment:
- **Persistence Strategy**: High-concurrency binary and JSON-based storage for user sessions and historical logging.
- **Alert Implementations**: Concrete strategies for handling emergency dispatches, logging, and contact notifications.

### 5. UI Layer (Reactive Interface)
A state-of-the-art JavaFX implementation using the Model-View-Controller (MVC) pattern:
- **Asynchronous Execution**: All network and heavy logic operations are offloaded from the Application Thread to prevent UI freezing during critical moments.
- **CSS-Driven Design**: A professional, high-contrast theme optimized for readability in emergency contexts.

## Key Features and Capabilities

### 🧠 Hardware-Aware Native Intelligence (SoterIA 2.0)
SoterIA 2.0 introduces a state-of-the-art **Tiered Scaling Architecture** that dynamically provisions AI resources based on the device's physical hardware. All LLMs are shipped as **GGUF** single-file bundles for reproducible, dependency-free deployment.

#### RAM-Based 5-Tier Scaling (GGUF via llama.cpp):
- **Ultra-Lite (< 3GB)**: Qwen3-0.6B Q4_K_M (Reasoning) + Vosk Small (STT).
- **Lite (3 - 4GB)**: Gemma 3n E2B-it Q4_K_M (Reasoning) + Vosk Small (STT).
- **Balanced (4 - 6GB)**: Gemma 3n E2B-it Q8_0 (Reasoning) + Vosk Standard (STT).
- **Performance (6 - 12GB)**: Gemma 3n E4B-it Q4_K_M (Reasoning) + Vosk Standard (STT).
- **Ultra (≥ 12GB)**: Gemma 3n E4B-it Q8_0 High-Precision (Reasoning) + Vosk Standard (STT).

> **Roadmap**: upgrade a Gemma 4 (E2B/E4B) pendiente de `de.kherud:llama` >= 4.3.0 con soporte nativo para la arquitectura gemma4.

### 🏥 100% Offline RAG Pipeline
Survival-critical reliability via a local **Retrieval-Augmented Generation** pipeline:
- **Apache Lucene**: High-speed, in-memory indexing of professional medical protocols.
- **llama.cpp (via `de.kherud:llama`)**: Native execution of GGUF LLMs with CPU, Metal (macOS/iOS) and Vulkan (Android) backends.
- **JGraphT**: Graph-based relationship modeling for medical triage.

### 🌍 English-Core Multilingual Strategy
Using an "English-Core" reasoning approach for maximum precision:
- **Cross-Lingual Reasoning**: The LLM reads English protocols and generates instructions in the user's selected language (e.g., Spanish).
- **Semantic Mapping**: Multilingual keyword indexing allows users to trigger English protocols using native terminology.

### Advanced Emergency Classification
The system identifies and prepares unique protocols for various critical categories:
- **Medical Emergencies**: Specialized protocols for clinical events ( CPR, Hemorrhage, Choking ).
- **Neurological**: FAST-assessment based stroke detection.
- **Environmental**: Thermal burns and trauma management.

### Multimodal Input Handling
- **Conversational Synthesis**: A guided UX that asks the right questions based on the detected emergency type.
- **Voice-to-Action**: Real-time native voice processing (Vosk) allows hands-free reporting.

### Comprehensive Operational Logging
Every interaction is audited and stored in three specialized tracks:
1. **Emergency History**: Chronological records of all detected events.
2. **Alert Registry**: Detailed logs of every outbound notification.
3. **User Feedback**: Post-event evaluation for system refinement.

## Project Structure

```text
com.soteria.core
├── model              # Immutable data records (UserData, EmergencyEvent)
└── interfaces         # Service contracts for DI (AlertService, IntelligenceProvider)

com.soteria.logic
└── EmergencyDetector  # Core business logic and detection orchestration

com.soteria.infrastructure
├── intelligence       # AI Service clients and STT/TTS implementations
├── persistence        # Secure data storage (JSON/Binary)
└── alerts             # Concrete alert strategies and logging implementations

com.soteria.ui
├── controller         # MVC Controllers for View management
└── MainApp            # Application entry point and lifecycle management
```

## System Implementation Standards

### OOP Excellence
- **Polymorphism**: Unified treatment of multiple alert strategies through `AlertService`.
- **Encapsulation**: Strict access modifiers and record-based data transport.
- **Abstraction**: Complex detection logic hidden behind simplified service facades.

### Resilience and Error Handling
- **Multi-Level Validation**: Input filtering at the UI, Logic, and Persistence layers.
- **Service Availability Check**: Real-time heartbeat monitoring of intelligence backends with automated fallback to Basic Mode.

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