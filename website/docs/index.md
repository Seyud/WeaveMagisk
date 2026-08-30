---
layout: home
title: Home

head:
  # LCP 优化：高优先级预载 Hero 图（head 里的 URL 不会自动补 base，需手写前缀）
  - - link
    - rel: preload
      as: image
      href: /WeaveMask/logo.webp
      fetchpriority: high

hero:
  name: WeaveMask
  text: A differentiated and improved Magisk fork
  tagline: ""
  image:
    src: /logo.webp
    alt: WeaveMask
  actions:
    - theme: brand
      text: Get started
      link: /guide/what-is-weavemask
    - theme: alt
      text: View on GitHub
      link: https://github.com/Seyud/WeaveMask

features:
  - title: Magisk based
    details: Built on top of the powerful Magisk framework, providing a solid foundation for Android root management.
  - title: Enhanced features
    details: Additional improvements and optimizations over the original Magisk for better user experience.
  - title: WebUI support
    details: Fully compatible with KernelSU WebUI API, enabling modules to provide graphical interfaces directly within the WeaveMask app.
  - title: Active development
    details: Continuously maintained and updated to support the latest Android versions and devices.
