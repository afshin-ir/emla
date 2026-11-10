(()=>{
  const phrase="طراحی سایت و توسعهٔ اپلیکیشن اَفرا توسط: افشین فلاطونی © ۱۴۰۵";
  let footer=document.querySelector('.site-footer');
  if(!footer){
    footer=document.createElement('footer');
    footer.className='site-footer';
    document.body.appendChild(footer);
  }

  let contact=document.getElementById('site-contact-link');
  if(!contact){
    contact=document.createElement('a');
    contact.id='site-contact-link';
    contact.className='site-contact-link';
    contact.href='/emla/contact.html';
    contact.textContent='تماس با ما';
  }
  if(contact.parentElement!==footer) footer.insertBefore(contact,footer.firstChild);

  let note=footer.querySelector('.site-credit')||document.querySelector('.site-credit');
  if(!note){
    note=document.createElement('p');
    footer.appendChild(note);
  }else if(note.parentElement!==footer){
    note.remove();
    footer.appendChild(note);
  }
  note.className='site-credit';
  note.textContent=phrase;

  if(!document.getElementById('site-footer-style')){
    const style=document.createElement('style');
    style.id='site-footer-style';
    style.textContent='.site-footer{max-width:1440px;margin:22px auto 0;padding:0 24px 16px;display:flex;flex-direction:column;align-items:center;gap:8px}.site-contact-link{color:#16765d;font-size:.76rem;font-weight:600;line-height:1.7;text-decoration:none;text-underline-offset:3px}.site-contact-link:hover{text-decoration:underline}.site-contact-link:focus-visible{outline:2px solid #16765d;outline-offset:3px;border-radius:3px}.site-credit{width:100%;margin:0;padding:12px 8px 0;border-top:1px solid #e1e9e4;text-align:center;color:#89958e;font-size:.72rem;line-height:1.8;opacity:.82}.dark .site-credit{border-color:#34483e;color:#9aa8a0}.dark .site-contact-link{color:#a9dac2}@media(max-width:580px){.site-footer{padding:0 13px 14px}.site-credit{font-size:.68rem}}';
    document.head.appendChild(style);
  }
})();