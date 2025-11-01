import tensorflow as tf
from tensorflow.keras import layers, models
import os
import tempfile

# 112x112x3 giriş -> 128-dim L2 normalize embedding
INPUT_SHAPE = (112, 112, 3)

def build_tiny_embedder():
    inp = layers.Input(shape=INPUT_SHAPE)
    x = layers.Rescaling(1./255)(inp)               # [0,1] normalizasyon
    # ... (Diğer katmanlar aynı) ...
    x = layers.Conv2D(128, 3, padding="same", activation="relu")(x)
    x = layers.GlobalAveragePooling2D()(x)
    
    x = layers.Dense(128, use_bias=False, name="embed_output")(x)
    
    # L2 Normalizasyonu için Lambda katmanı
    x = layers.Lambda(lambda t: tf.math.l2_normalize(t, axis=-1), 
                      name="l2norm")(x)
                      
    return models.Model(inp, x, name="tiny_embedder_112")

model = build_tiny_embedder()
model.summary()

# --- GÜNCELLENMİŞ DÖNÜŞÜM ADIMLARI ---

# 1. Modeli geçici bir konuma SavedModel formatında EXPORT edin
temp_dir = tempfile.mkdtemp()
saved_model_path = os.path.join(temp_dir, "saved_model_for_tflite") # Klasör yolu

# model.save() yerine model.export() kullanıyoruz.
# Bu, TFLite/TFServing gibi downstream araçlar için SavedModel üretmenin yeni yoludur.
model.export(saved_model_path) 
print(f"Model SavedModel formatında dışa aktarıldı: {saved_model_path}")

# 2. Modeli SavedModel formatından TFLite'a çevirin
# Artık Keras değil, doğrudan SavedModel yolunu kullanıyoruz.
converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_path)

# İstersen dosyayı küçültmek için optimize aç:
# converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite = converter.convert()

with open("mobile_face_net.tflite", "wb") as f:
    f.write(tflite)

print("OK -> mobile_face_net.tflite üretildi")