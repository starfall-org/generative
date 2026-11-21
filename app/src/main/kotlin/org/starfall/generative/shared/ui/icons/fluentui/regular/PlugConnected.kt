package org.starfall.generative.shared.ui.icons.fluentui.regular

/*
MIT License

Copyright (c) 2020 Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val FluentuiSystemIconsPlugConnected: ImageVector
    get() {
        if (_FluentuiSystemIconsPlugConnected != null) return _FluentuiSystemIconsPlugConnected!!

        _FluentuiSystemIconsPlugConnected = ImageVector.Builder(
            name = "plug-connected",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black)
            ) {
                moveTo(19.4898f, 5.57084f)
                curveTo(20.2796f, 6.58684f, 20.7499f, 7.8635f, 20.7499f, 9.25001f)
                curveTo(20.7499f, 11.5333f, 19.4745f, 13.5187f, 17.5972f, 14.5327f)
                curveTo(16.9481f, 14.8833f, 16.1671f, 14.6672f, 15.6454f, 14.1455f)
                lineTo(9.85438f, 8.35451f)
                curveTo(9.33268f, 7.8328f, 9.1166f, 7.05181f, 9.46723f, 6.40265f)
                curveTo(10.4812f, 4.52541f, 12.4666f, 3.25001f, 14.7499f, 3.25001f)
                curveTo(16.1364f, 3.25001f, 17.4132f, 3.72034f, 18.4292f, 4.51016f)
                lineTo(20.7197f, 2.21967f)
                curveTo(21.0126f, 1.92678f, 21.4874f, 1.92678f, 21.7803f, 2.21967f)
                curveTo(22.0732f, 2.51256f, 22.0732f, 2.98744f, 21.7803f, 3.28033f)
                lineTo(19.4898f, 5.57084f)
                close()
                moveTo(17.4732f, 12.8331f)
                curveTo(18.5534f, 12.0106f, 19.2499f, 10.7106f, 19.2499f, 9.25001f)
                curveTo(19.2499f, 6.76473f, 17.2352f, 4.75001f, 14.7499f, 4.75001f)
                curveTo(13.2893f, 4.75001f, 11.9893f, 5.44648f, 11.1668f, 6.52671f)
                curveTo(10.9008f, 6.87593f, 10.9812f, 7.35998f, 11.2916f, 7.67036f)
                lineTo(16.3295f, 12.7083f)
                curveTo(16.6399f, 13.0187f, 17.124f, 13.0991f, 17.4732f, 12.8331f)
                close()
                moveTo(3.28033f, 21.7803f)
                lineTo(5.57072f, 19.4899f)
                curveTo(6.58673f, 20.2797f, 7.86339f, 20.75f, 9.24989f, 20.75f)
                curveTo(11.5332f, 20.75f, 13.5186f, 19.4746f, 14.5325f, 17.5973f)
                curveTo(14.8832f, 16.9482f, 14.6671f, 16.1672f, 14.1454f, 15.6455f)
                lineTo(8.35438f, 9.85448f)
                curveTo(7.83268f, 9.33278f, 7.05168f, 9.1167f, 6.40253f, 9.46733f)
                curveTo(4.52528f, 10.4813f, 3.24989f, 12.4667f, 3.24989f, 14.75f)
                curveTo(3.24989f, 16.1366f, 3.72022f, 17.4133f, 4.51005f, 18.4293f)
                lineTo(2.21967f, 20.7197f)
                curveTo(1.92678f, 21.0126f, 1.92678f, 21.4874f, 2.21967f, 21.7803f)
                curveTo(2.51256f, 22.0732f, 2.98744f, 22.0732f, 3.28033f, 21.7803f)
                close()
                moveTo(7.67023f, 11.2917f)
                lineTo(12.7082f, 16.3296f)
                curveTo(13.0186f, 16.64f, 13.0989f, 17.1241f, 12.833f, 17.4733f)
                curveTo(12.0105f, 18.5535f, 10.7105f, 19.25f, 9.24989f, 19.25f)
                curveTo(6.76461f, 19.25f, 4.74989f, 17.2353f, 4.74989f, 14.75f)
                curveTo(4.74989f, 13.2894f, 5.44635f, 11.9894f, 6.52659f, 11.1669f)
                curveTo(6.87581f, 10.9009f, 7.35985f, 10.9813f, 7.67023f, 11.2917f)
                close()
            }
        }.build()

        return _FluentuiSystemIconsPlugConnected!!
    }

private var _FluentuiSystemIconsPlugConnected: ImageVector? = null
