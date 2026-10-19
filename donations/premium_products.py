"""Authoritative prices in integer hundredths; a month retains the existing 30-day contract."""
PRODUCTS = {
    'premiumlite': {'table': 'premium_lite', 'prices': {30: 31900, 60: 54900, 180: 149000, 360: 269000}},
    'premium': {'table': 'premium', 'prices': {30: 60000, 60: 100000, 180: 270000, 360: 480000}},
}


def product_spec(product):
    if not isinstance(product, str) or product not in PRODUCTS:
        raise ValueError('Unknown premium product')
    return PRODUCTS[product]
