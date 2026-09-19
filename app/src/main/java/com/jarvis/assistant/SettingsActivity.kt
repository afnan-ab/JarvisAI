package com.jarvis.assistant

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: VoiceSettings
    private lateinit var voice: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = VoiceSettings(this)
        voice = VoiceManager(this, {}, {}, {})
        voice.init()

        val pitchSlider = findViewById<SeekBar>(R.id.pitchSlider)
        val rateSlider = findViewById<SeekBar>(R.id.rateSlider)
        val pitchLabel = findViewById<TextView>(R.id.pitchLabel)
        val rateLabel = findViewById<TextView>(R.id.rateLabel)
        val maleRadio = findViewById<RadioButton>(R.id.maleRadio)
        val femaleRadio = findViewById<RadioButton>(R.id.femaleRadio)

        val savedPitch = settings.getPitch()
        val savedRate = settings.getRate()
        pitchSlider.progress = (((savedPitch - 0.5f) / 1.0f) * 100).toInt().coerceIn(0, 100)
        rateSlider.progress = (((savedRate - 0.5f) / 1.0f) * 100).toInt().coerceIn(0, 100)
        pitchLabel.text = "Pitch: ${(savedPitch * 100).toInt()}%"
        rateLabel.text = "Speed: ${(savedRate * 100).toInt()}%"
        if (settings.getPreferMale()) maleRadio.isChecked = true else femaleRadio.isChecked = true

        fun currentPitch(): Float = 0.5f + (pitchSlider.progress / 100f)
        fun currentRate(): Float = 0.5f + (rateSlider.progress / 100f)
        fun persistAndApply() {
            settings.save(currentPitch(), currentRate(), maleRadio.isChecked)
            voice.applySettings()
        }

        pitchSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pitchLabel.text = "Pitch: ${(currentPitch() * 100).toInt()}%"
                if (fromUser) persistAndApply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rateSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                rateLabel.text = "Speed: ${(currentRate() * 100).toInt()}%"
                if (fromUser) persistAndApply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        maleRadio.setOnClickListener { persistAndApply() }
        femaleRadio.setOnClickListener { persistAndApply() }

        findViewById<Button>(R.id.presetDeep).setOnClickListener {
            pitchSlider.progress = 30; rateSlider.progress = 40
            pitchLabel.text = "Pitch: ${(currentPitch() * 100).toInt()}%"
            rateLabel.text = "Speed: ${(currentRate() * 100).toInt()}%"
            persistAndApply()
        }
        findViewById<Button>(R.id.presetWarm).setOnClickListener {
            pitchSlider.progress = 50; rateSlider.progress = 50
            pitchLabel.text = "Pitch: ${(currentPitch() * 100).toInt()}%"
            rateLabel.text = "Speed: ${(currentRate() * 100).toInt()}%"
            persistAndApply()
        }
        findViewById<Button>(R.id.presetBright).setOnClickListener {
            pitchSlider.progress = 65; rateSlider.progress = 60
            pitchLabel.text = "Pitch: ${(currentPitch() * 100).toInt()}%"
            rateLabel.text = "Speed: ${(currentRate() * 100).toInt()}%"
            persistAndApply()
        }

        findViewById<Button>(R.id.testVoiceButton).setOnClickListener {
            voice.speak("This is how I sound.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.release()
    }
}
