# 🚨 Sistema de Gestión de Emergencias Inteligente

## 📋 Descripción del Proyecto

**Sistema de Gestión de Emergencias** es una aplicación Java con interfaz gráfica JavaFX que proporciona un sistema completo para detectar, procesar y registrar emergencias. El sistema incluye un chat conversacional con IA que guía al usuario a través de un flujo estructurado de detección de emergencias, envío de alertas a servicios de emergencia (112) y recopilación de feedback posterior.

Diseñado como un prototipo educativo, el proyecto demuestra principios sólidos de **Programación Orientada a Objetos (POO)**, incluyendo:
- ✅ **Interfaces** para abstracciones
- ✅ **Herencia** con clases abstractas
- ✅ **Polimorfismo** mediante implementaciones
- ✅ **Control de errores** robusto
- ✅ **Validaciones** exhaustivas
- ✅ **Interfaz gráfica** con JavaFX

Autor: **Mircea Mihai Bontoi**
---

## 🎯 Características Principales

### 1. **Interfaz Gráfica Moderna (JavaFX)**
- Pantalla de login/registro con diseño moderno
- Chat conversacional con IA
- Reconocimiento de voz
- Tema visual profesional

### 2. **Detección de Emergencias con IA**
El sistema puede detectar emergencias mediante:
- **Análisis de texto**: Describe lo que está pasando
- **Reconocimiento de voz**: Presiona el botón de micrófono
- **Clasificación automática**: IA identifica el tipo de emergencia
- **Fallback manual**: Menú de opciones si la IA no está disponible

Tipos de emergencia soportados:
- 🚗 Accidente de tráfico
- 🏥 Problema médico
- 🔥 Incendio
- ⚔️ Agresión
- 🌊 Desastre natural

### 3. **Registro y Logging**
Todas las emergencias se registran automáticamente en archivos de log:
- `logs/emergency_history.log` - Historial de emergencias
- `logs/emergency_alerts.log` - Alertas enviadas
- `logs/user_feedback.log` - Feedback de usuarios

### 4. **Sistema de Alertas Flexible**
Implementación de múltiples estrategias de alerta mediante interfaces:
- **AlertSender**: Envío de alertas al 112
- **EmergencyLogger**: Registro de eventos
- Extensible para SMS, Email, etc.

### 5. **Control de Errores Integral**
- Validación de entrada del usuario
- Manejo de excepciones en todas las operaciones
- Reintentos automáticos en campos requeridos
- Mensajes de error claros y descriptivos

---

## 🏗️ Arquitectura y Diseño POO

### Diagrama de Clases (Simplificado)

```
┌─────────────────────────────────────────────────────────┐
│                     MainApp                             │
│          (Aplicación JavaFX principal)                  │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│                  ChatController                         │
│        (Controlador de chat conversacional)             │
├─────────────────────────────────────────────────────────┤
│ - EmergencyDetector (detecta emergencias)               │
│ - IAlert alertSender (envía alertas - POLIMORFISMO)    │
│ - EmergencyLogger (registra eventos)                    │
│ - UserData (datos del usuario)                          │
│ - AIClassifierClient (comunicación con IA)              │
└─────────────────────────────────────────────────────────┘

INTERFACES (Contratos):
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│     IAlert       │  │ ILocationService │  │     ILogger      │
├──────────────────┤  ├──────────────────┤  ├──────────────────┤
│ send()           │  │ getCoordinates() │  │ logInfo()        │
│ notifyContacts() │  │ getLocation()    │  │ logWarning()     │
│ getAlertType()   │  │ getPermission()  │  │ logError()       │
└──────────────────┘  └──────────────────┘  └──────────────────┘
       ▲                      ▲                      ▲
       │ implementa           │ implementa           │ implementa
       │                      │                      │
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  AlertSender     │  │ GPSLocationService│ │ EmergencyLogger  │
└──────────────────┘  └──────────────────┘  └──────────────────┘

HERENCIA (Extensibilidad):
┌──────────────────────────────┐
│  EmergencyType (ABSTRACTA)   │
│  ├─ getPriority()            │
│  ├─ getDescription()         │
│  ├─ getResponseProtocol()    │ (método abstracto)
│  └─ getRequiredServices()    │ (método abstracto)
└──────────┬───────────────────┘
           │
           ▼
┌──────────────────────────────┐
│  MedicalEmergency            │
│  (Implementa métodos)        │
└──────────────────────────────┘
```

### Conceptos POO Implementados

#### 1. **Polimorfismo** 🔄
```java
private final IAlert alertSender;  // Interfaz genérica
this.alertSender = new AlertSender();  // Implementación específica

// En runtime, puede ser cualquier clase que implemente IAlert:
// - AlertSender
// - CallAlert
// - SMSAlert (futura)
// - EmailAlert (futura)
```

#### 2. **Herencia** 👨‍👧
```java
public abstract class EmergencyType {
    abstract String getResponseProtocol();
    abstract String[] getRequiredServices();
}

public class MedicalEmergency extends EmergencyType {
    // Implementación específica para emergencias médicas
}
```

#### 3. **Interfaces** 📋
```java
public interface IAlert {
    boolean send(EmergencyEvent event);
    void notifyContacts(UserData userData, EmergencyEvent event);
    String getAlertType();
}
```

#### 4. **Reutilización de Código** ♻️
```java
// EmergencyDetector es usado tanto por la UI como por la lógica de negocio
public class ChatController {
    private EmergencyDetector detector;
    
    public void processMessage(String message) {
        EmergencyDetector.DetectionResult result = detector.classifyEmergency(message);
        // Reutiliza la lógica de detección
    }
}
```

---

## 📁 Estructura del Proyecto

```
src/main/java/com/emergencias/
│
├── alert/                             # Sistema de alertas
│   ├── AlertSender.java              # Implementa IAlert
│   ├── CallAlert.java                # Alternativa: llamadas
│   └── EmergencyLogger.java          # Registro de eventos
│
├── detector/                          # Detección de emergencias
│   └── EmergencyDetector.java        # Detecta y clasifica emergencias
│
├── model/                             # Modelos de datos
│   ├── CentroSalud.java              # Modelo de centro de salud
│   ├── CentroSaludUtils.java         # Utilidades para centros de salud
│   ├── EmergencyEvent.java           # Evento de emergencia
│   ├── EmergencyType.java            # Clase abstracta para tipos
│   ├── MedicalEmergency.java         # Implementación: emergencia médica
│   ├── UserData.java                 # Información del usuario
│   └── UserFeedback.java             # Feedback del usuario
│
├── services/                          # Servicios e interfaces
│   ├── AIClassifierClient.java       # Cliente para backend Python
│   ├── GPSLocationService.java       # Implementación GPS
│   ├── IAlert.java                   # Interfaz de alertas
│   ├── ILocationService.java         # Interfaz de ubicación
│   └── ILogger.java                  # Interfaz de logging
│
└── ui/                                # Interfaz de usuario (JavaFX)
    ├── ChatController.java           # Controlador de chat conversacional
    ├── LoginController.java          # Controlador de login/registro
    ├── MainApp.java                  # Aplicación principal JavaFX
    ├── MainController.java           # Controlador principal
    └── UserFileManager.java          # Gestión de archivos de usuario

src/main/resources/
├── fxml/
│   ├── chat-view.fxml               # Vista de chat
│   └── login-view.fxml              # Vista de login
├── styles/
│   └── main.css                     # Estilos CSS
├── CentrosdeSaludMurcia.json        # Datos de centros de salud
└── META-INF/
    └── MANIFEST.MF                  # Manifiesto JAR

python-backend/                        # Backend Python para IA
├── server.py                        # Servidor FastAPI
├── train_model.py                   # Entrenamiento de modelo
├── requirements.txt                 # Dependencias Python
├── data/                           # Datos de entrenamiento
└── models/                         # Modelos entrenados
```

---

## 🔄 Flujo de Ejecución

### 1. **Inicialización de la Aplicación**
```
MainApp.java
  ├─ Cargar pantalla de login (login-view.fxml)
  ├─ Configurar estilos CSS
  ├─ Verificar sesión guardada
  └─ Mostrar ventana principal
```

### 2. **Login/Registro**
```
LoginController.java
  ├─ Validar credenciales
  ├─ Registrar nuevos usuarios
  ├─ Guardar sesión (opcional)
  └─ Navegar a pantalla de chat
```

### 3. **Chat Conversacional**
```
ChatController.java
  ├─ Inicializar EmergencyDetector
  ├─ Verificar disponibilidad de IA
  ├─ Procesar mensajes del usuario
  └─ Clasificar emergencias
```

### 4. **Detección de Emergencia**
```
EmergencyDetector.classifyEmergency()
  ├─ Si IA disponible:
  │   ├─ Enviar descripción al backend Python
  │   ├─ Recibir clasificación
  │   ├─ Extraer tipo, confianza, instrucciones
  │   └─ Retornar DetectionResult
  └─ Si IA no disponible:
      ├─ Análisis manual por palabras clave
      └─ Retornar DetectionResult
```

### 5. **Envío de Alerta**
```
sendEmergencyAlert()
  ├─ Crear EmergencyEvent
  ├─ Registrar en EmergencyLogger
  ├─ Enviar con AlertSender
  └─ Notificar al usuario
```

### 6. **Registros Generados**
```
logs/emergency_history.log:
[2026-01-11 14:30:45] ID: a1b2c3d4 | Tipo: Problema médico | Ubicación: Plaza Mayor, Madrid | Gravedad: 8

logs/emergency_alerts.log:
[2026-01-11 14:30:45] ALERTA DE EMERGENCIA
Tipo: Problema médico
Ubicación: Plaza Mayor, Madrid
Nivel de gravedad: 8/10
...

logs/user_feedback.log:
[2026-01-11 14:31:15] ID Emergencia: a1b2c3d4 | Puntuación: 5/5 | Comentarios: Excelente servicio
```

---

## 🛡️ Control de Errores

El sistema implementa manejo de errores multinivel:

### Nivel 1: Validación de Entrada
```java
// En LoginController.handleRegister()
if (username.isEmpty() || password.isEmpty() || name.isEmpty()) {
    registerErrorLabel.setText("Por favor completa los campos obligatorios");
    return;
}
```

### Nivel 2: Try-Catch en Operaciones Críticas
```java
try {
    EmergencyEvent event = detector.createEvent(lastDetectionResult, null, 5);
    logger.logEmergency(event);
    boolean sent = alertSender.send(event);
} catch (Exception e) {
    addBotMessage("❌ Error: " + e.getMessage());
}
```

### Nivel 3: Verificación de Servicios Externos
```java
private void checkAIAvailability() {
    new Thread(() -> {
        aiAvailable = aiClient.isAvailable();
        Platform.runLater(() -> {
            if (aiAvailable) {
                aiStatusLabel.setText("IA: ✅ Conectada");
            } else {
                aiStatusLabel.setText("IA: ❌ Desconectada");
                addBotMessage("⚠️ Servidor de IA no disponible.\nModo básico activado.");
            }
        });
    }).start();
}
```

---

## 🚀 Instrucciones de Uso

### Requisitos Previos
- **Java 21** o superior
- **Maven** para dependencias
- **Python 3.8+** (opcional, para IA)
- **Dependencias Python** (para funcionalidad completa de IA)

### Ejecución

1. **Compilar el proyecto:**
   ```bash
   mvn clean compile
   ```

2. **Ejecutar la aplicación:**
   ```bash
   mvn javafx:run
   ```

3. **(Opcional) Iniciar backend Python:**
   ```bash
   cd python-backend
   pip install -r requirements.txt
   python -m uvicorn server:app --host 0.0.0.0 --port 8000
   ```

### Funcionalidades sin Backend Python
Si el servidor Python no está disponible:
- ✅ Login/Registro funciona
- ✅ Chat funciona en modo básico
- ✅ Detección manual de emergencias
- ✅ Envío de alertas
- ❌ Reconocimiento de voz
- ❌ Clasificación con IA
- ❌ Corrección ortográfica

---

## 🎨 Interfaz de Usuario

### Pantalla de Login
- Formulario de inicio de sesión
- Formulario de registro
- Recordar sesión
- Validación de campos

### Pantalla de Chat
- Chat conversacional con mensajes de usuario y bot
- Botón de micrófono para entrada por voz
- Indicador de estado de IA
- Mensajes de bienvenida personalizados
- Instrucciones de actuación para cada emergencia

---

## 🤝 Contribución y Mejoras Futuras

Posibles mejoras para versiones futuras:

- [ ] Integración con API de Google Maps para GPS real
- [ ] Base de datos en lugar de archivos de texto
- [ ] Envío real de SMS/Email
- [ ] Integración con servicios de emergencia reales
- [ ] Análisis de estadísticas de emergencias
- [ ] Sistema de autenticación de usuarios
- [ ] Aplicación móvil (Android/iOS)
- [ ] Soporte para múltiples idiomas
- [ ] Modo offline con sincronización

---

## 📝 Notas Técnicas

### Refactorización Reciente
- **EmergencyDetector** refactorizado para funcionar con JavaFX
- **ChatController** reutiliza EmergencyDetector (sin duplicación)
- Eliminados archivos obsoletos: ConsoleApp.java, EmergencyManager.java
- Estructura de paquetes optimizada

### Dependencias Principales
- **JavaFX 21.0.1**: Interfaz gráfica
- **Jackson 2.17.0**: Procesamiento JSON
- **SQLite JDBC**: Base de datos (futuro)
- **FastAPI**: Backend Python para IA

---

## 📄 Licencia

Este proyecto es un prototipo educativo desarrollado por **Mircea Mihai Bontoi**.