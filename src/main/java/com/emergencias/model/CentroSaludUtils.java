package com.emergencias.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.List;

public class CentroSaludUtils {
    
    /**
     * Carga la lista de centros de salud desde un archivo JSON en el classpath (resources).
     * 
     * @param nombreArchivo El nombre del archivo (ej: "CentrosdeSaludMurcia.json")
     * @return Lista de objetos CentroSalud o null si hay error
     */
    public static List<CentroSalud> cargarCentros(String nombreArchivo) {
        try {
            // 1. Obtener el archivo como un flujo de datos (InputStream) desde los recursos
            // Esto funciona tanto en el IDE como dentro de un JAR
            InputStream inputStream = CentroSaludUtils.class.getClassLoader().getResourceAsStream(nombreArchivo);

            if (inputStream == null) {
                System.err.println("❌ Error: No se encontró el archivo '" + nombreArchivo + "' en los recursos.");
                return null;
            }

            // 2. Usar Jackson para leer directamente del InputStream
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(inputStream, new TypeReference<List<CentroSalud>>(){});
            
        } catch (Exception e) {
            System.err.println("❌ Error leyendo centros de salud: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
