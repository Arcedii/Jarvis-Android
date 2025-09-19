package com.example.jarvis_android

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri

/**
 * Класс для обработки команд и воспроизведения звуков из папки assets.
 */
class CommandHandler(private val context: Context) {

    // Явный список WAV-файлов, лежащих в папке assets (можете дополнить)
    private val assetWavFiles = listOf(
        "Без проблем сэр.wav",
        "Всегда к вашим услугам сэр.wav",
        "Да сэр.wav",
        "Конечно сэр.wav",
        "Сделаю сэр.wav",
        "Сею секунду сэр.wav"
    )

    /** Проигрывает случайный WAV-файл из assets. */
    fun playRandomAudio() {
        if (assetWavFiles.isEmpty()) return
        val fileName = assetWavFiles.random()

        try {
            val afd = context.assets.openFd(fileName)
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mediaPlayer.setOnCompletionListener { player -> player.release() }
            mediaPlayer.prepare()
            mediaPlayer.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Открывает сайт погоды. */
    fun openWeather() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gismeteo.com"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Открывает карты. */
    fun openMap() {
        val gmmIntentUri = Uri.parse("geo:0,0?q=кафе")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(mapIntent)
    }

    /** Открывает музыкальный сервис. */
    fun playMusic() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.yandex.ru"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}