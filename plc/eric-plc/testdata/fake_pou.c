#include <stdint.h>

/* Same symbols RuSTy emits for the example addresses. The scan tests use this
   stand-in so they do not download the compiler. SealIn_spin burns time so a
   short period overruns. */

unsigned char __PI_0_0;
unsigned char __PI_0_1;
int16_t __PI_2;
unsigned char __PI_1_0;
int16_t __PI_4;

static int seal;

void SealIn(void *self) {
    (void)self;
    seal = (__PI_0_0 || seal) && !__PI_0_1;
    __PI_1_0 = (unsigned char)seal;
    __PI_4 = (int16_t)(((int32_t)__PI_2 * 1000) / 32767);
}

void SealIn_spin(void *self) {
    (void)self;
    volatile unsigned long i;
    for (i = 0; i < 150000000UL; i++) {
    }
}
