//! Output scale derived from the Android display density.

use smithay::output::Scale;

/// Device pixel ratio for the nested output.
///
/// Android reports density in dpi buckets; `dpi / 160` is the exact device
/// pixel ratio (`420 dpi` → `2.625`). The value is kept fractional so
/// `wp_fractional_scale_v1` clients and toolkit integer fallbacks both agree
/// with the panel instead of snapping to a guess.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct OutputScale {
    fractional: f64,
}

impl OutputScale {
    pub const MIN: f64 = 0.5;
    pub const MAX: f64 = 4.0;

    /// Clamp out nonsense from a platform call, keeping the value finite.
    pub fn sanitized(scale: f64) -> Self {
        let value = if scale.is_finite() {
            scale.clamp(Self::MIN, Self::MAX)
        } else {
            1.0
        };
        Self { fractional: value }
    }

    pub fn fractional(self) -> f64 {
        self.fractional
    }

    /// Integer fallback for clients that only understand whole output scales.
    pub fn integer_fallback(self) -> i32 {
        (self.fractional.ceil() as i32).max(1)
    }

    pub fn smithay(self) -> Scale {
        Scale::Fractional(self.fractional)
    }

    /// Logical size a physical buffer size maps to at this scale.
    pub fn logical_size(self, physical: (i32, i32)) -> (i32, i32) {
        (
            extent(physical.0, self.fractional),
            extent(physical.1, self.fractional),
        )
    }

    pub fn to_logical(self, physical: f64) -> f64 {
        physical / self.fractional
    }
}

fn extent(physical: i32, scale: f64) -> i32 {
    if physical <= 0 {
        0
    } else {
        ((physical as f64 / scale).round() as i32).max(1)
    }
}
