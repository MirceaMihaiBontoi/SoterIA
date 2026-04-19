"""
Configuración global de pytest: añade el directorio python-backend al sys.path
para que los tests puedan importar server.py y los servicios sin instalar el
proyecto como paquete.
"""
import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))
