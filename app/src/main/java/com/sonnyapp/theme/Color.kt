package com.sonnyapp.theme

import androidx.compose.ui.graphics.Color

/*
 * 「暗房 Darkroom」配色 —— 摄影工具的气质：安静、专业、让照片成为唯一的高光。
 *
 *  - 近黑暖调底色（非纯黑，OLED 友好且不生硬）
 *  - 单一琥珀强调色（暗房安全灯）：主操作 / 选中 / 已下载徽标
 *  - 不用 Material You 动态取色：它会从壁纸取色，界面可能变成粉的绿的，
 *    干扰用户对画面的判断。相机类界面应当尽量「消失」。
 */

/* 背景层级：从最底到浮层 */
val Ink0 = Color(0xFF0C0C0E)
val Ink1 = Color(0xFF17171A)
val Ink2 = Color(0xFF1E1E22)
val Ink3 = Color(0xFF26262B)

/* 文字层级（暖象牙白） */
val TextHi = Color(0xFFF2EFE9)
val TextMid = Color(0xFFB9B4AA)
val TextLo = Color(0xFF8E8A82)

/* 强调色：暗房安全灯的琥珀 —— 只给主操作与选中态 */
val Accent = Color(0xFFE8A33D)
val AccentPressed = Color(0xFFB87D28)

/* 状态色（低饱和，不抢照片） */
val OkGreen = Color(0xFF7BC96F)
val WarnAmber = Color(0xFFD9A441)
val ErrorRed = Color(0xFFE2574C)

/* 半透明浮层底色（压在照片上） */
val ScrimTop = Color(0xCC000000)
val ScrimBottom = Color(0xB3000000)
