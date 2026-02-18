package com.emergencias.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.List;

public class CentroSaludUtils {
    
    /**
     * Carga la lista de centros de salud desde un archivo JSON en el classpath.
     */
    public static List<CentroSalud> cargarCentros(String rutaArchivo) {
        try {
            // CAMBIO: Usar Class.getResourceAsStream en lugar de ClassLoader.getResourceAsStream.
            // Este método es a veces más fiable para encontrar recursos.
            InputStream inputStream = CentroSaludUtils.class.getResourceAsStream(rutaArchivo);

            if (inputStream == null) {
                System.err.println("❌ Error: No se encontró el archivo '" + rutaArchivo + "' en los recursos.");
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(inputStream, new TypeReference<List<CentroSalud>>(){});
            
        } catch (Exception e) {
            System.err.println("❌ Error leyendo centros de salud: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
